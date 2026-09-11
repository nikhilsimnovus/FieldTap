# Uploading cellular measurement data: the legal and privacy reality

**Status:** engineering research input for counsel. **This is not legal advice.**
Nobody on this team is a lawyer. Everything below is a reading of primary sources
by an engineer, assembled so that a qualified privacy/telecoms lawyer can be asked
sharp questions instead of open-ended ones. Several of the conclusions are
genuinely unsettled, and those are marked as such rather than smoothed over.

**Date:** 2026-09-10
**Scope:** the proposed FieldTap feature that uploads capture data to per-user
cloud accounts.

---

## 0. The one-paragraph version

Uploading a **public-API KPI log** (cell ID, RSRP, GPS track, operator) is an
ordinary, well-trodden location-data privacy problem: it is personal data, it
needs consent and a retention policy, and an entire industry (Opensignal, Tutela,
CellMapper, OpenCelliD) already does it at scale without visible legal trouble.

Uploading a **full diag capture** is a materially different act. It is not just
"more data" — it changes the legal category, because a diag capture contains
signalling addressed to **other people's** handsets, and in at least the UK,
Germany and the US there are separate criminal provisions that attach
specifically to *disclosing* radio messages of which you are not the intended
recipient. The "I captured my own phone" defence protects the user for their own
traffic and does **not** reach the paging records of the strangers around them.

The practical consequence for the product: **the two data classes must not share
an upload path.** KPI logs can go to the cloud under a normal consent-and-DPA
model. Raw diag must be redacted on-device before it ever leaves, or must not
leave at all.

---

## 1. What is actually in the data

### 1a. Public-API KPI logs (Android `TelephonyManager` / `CellInfo` class)

Typical contents:

| Field | Example | Personal data? |
| --- | --- | --- |
| MCC/MNC | 234-15 | No, alone |
| Cell identity (ECI/NCI), PCI, TAC | 0x1A2B3C | No, alone |
| Signal (RSRP/RSRQ/SINR/RSSI) | -95 dBm | No, alone |
| GPS lat/lon, timestamp | 51.5074, -0.1278 | **Yes** |
| Operator name, band, EARFCN/NRARFCN | "EE", n78 | No, alone |

The critical point: **the GPS track makes the whole record personal data**, and
it is personal data about **the user only**. There are no third-party identifiers
in this class. Under GDPR Recital 26 the test is whether a natural person can be
identified "directly or indirectly"; a timestamped location trace of a device
carried by one person is the textbook example of indirect identification, and a
home-and-workplace pattern re-identifies trivially.

Note also what the public API **cannot** give you: since **Android 10**, IMEI and
serial number require `READ_PRIVILEGED_PHONE_STATE`, which is a privileged
permission that Play-distributed apps cannot declare; attempting access throws
`SecurityException`. This is a useful architectural fact — a Play-store KPI app is
*structurally incapable* of collecting the worst identifiers. That is a
defensibility argument worth preserving deliberately.

**Verdict: personal data (because of location), about the user, and only the user.**

### 1b. Full diag capture (Qualcomm `/dev/diag`, QMDL/DLF → GSMTAP pcap)

Contents, by layer:

| Layer | Message | Identifiers present |
| --- | --- | --- |
| NAS (EMM/5GMM) | Attach/Registration Request, Identity Response, GUTI Reallocation | **IMSI/SUPI, IMEI/IMEISV, GUTI/5G-GUTI, TMSI/5G-S-TMSI** |
| RRC (DCCH) | RRC Connection Setup / Reconfiguration, Measurement Reports | C-RNTI, cell identities, **neighbour lists = topology** |
| RRC (BCCH) | MIB, SIB1..SIBn | Cell ID, TAC, PLMN, barring, frequency plan |
| **RRC (PCCH)** | **Paging** | **`ue-Identity` of up to 16 OTHER subscribers per message** |
| ML1 / measurement | Serving + neighbour cell measurements | Fine-grained RF fingerprint = location, even with GPS off |

Two categories of exposure, and they are legally distinct:

**(i) The user's own identifiers.** IMSI, IMEI, GUTI, TMSI belonging to the
handset doing the capture. These are personal data about the user. An IMSI is a
lifetime-stable subscription identifier; an IMEI is a lifetime-stable *hardware*
identifier that survives SIM swap. Together with a GPS track they are about as
identifying as data gets short of a name.

**(ii) Other subscribers' identifiers, from the paging channel.** This is the part
the team flagged, and it is real, not theoretical.

### 1c. The paging channel problem — confirmed real

The mechanism is structural, not a bug:

- An eNB/gNB combines the pending pages for many UEs into **one** RRC `Paging`
  message. The `PagingRecordList` holds **a maximum of 16 paging records**, each
  with a `ue-Identity` (S-TMSI, or in 5G, 5G-S-TMSI).
- A UE in idle mode wakes at its DRX paging occasion and **decodes the entire
  message**, then scans all records looking for its own. It cannot decode
  selectively — the records are in one PDU.
- The Qualcomm diag `LTE_RRC_OTA_Packet` log family carries PCCH alongside BCCH
  and DCCH. Wireshark's GSMTAP dissector has an explicit LTE-RRC PCCH channel
  type. So the paging PDU — with all 16 records — lands in the pcap verbatim.

So: **every idle-mode diag capture accumulates a rolling sample of the temporary
identifiers of other subscribers in the same tracking area.** Not a fringe case;
the ordinary steady-state behaviour of the tool.

Is a TMSI "just a random number"? No. The academic literature is consistent and
damning:

- Sørseth, Zhou, Mjølsnes & Olimid, *Experimental Analysis of Subscribers' Privacy
  Exposure by LTE Paging* (arXiv:1807.11350, 2018): paging messages "can be
  captured and decoded by using minimal technical skills and publicly available
  tools", tested against **three** live operators; they expose subscriber identity
  and location.
- Hussain et al., *Privacy Attacks to the 4G and 5G Cellular Paging Protocols*
  (NDSS 2019) — the ToRPEDO / PIERCER / IMSI-Cracking family. Verified against
  **all US carriers** and three Canadian ones. Findings that matter here:
  - TMSIs are supposed to rotate but frequently do not; persistence of **up to
    7 days** was observed, and other work reports up to 3 days in an urban area.
    A stable TMSI plus a location trace is a trackable pseudonymous identity.
  - **PIERCER**: some operators, contrary to 3GPP guidance, page using the
    **IMSI itself** rather than a TMSI. Which means a paging capture on the wrong
    network contains **other subscribers' permanent identifiers**, not just
    temporary ones.
  - TMSI→identity linking is a solved problem for an attacker who can place a
    call or send a silent SMS: correlate the paging occasion with the trigger.

That last bullet is what turns "meaningless hex" into personal data under GDPR
Recital 26's "means reasonably likely to be used" test. The linkage does not have
to be easy for *you*; it has to be reasonably likely for *someone*.

**Verdict: a diag capture is personal data about the user AND about an
indeterminate set of third parties who never consented and cannot be notified.**
Uploading it centralises exactly the dataset an IMSI-catcher produces.

### 1d. What the existing tools do about it: nothing

Checked directly. **QCSuper's README contains no discussion of privacy, of
IMSI/IMEI/TMSI in captures, of legality, or of redaction.** SCAT and MobileInsight
likewise ship no anonymisation feature. The entire open-source diag ecosystem
treats the output as a developer artefact and pushes the problem to the user.

That is not a precedent to copy. It is an unoccupied position in the market: no
existing diag tool offers redaction, so "the drive-test tool that is safe to
upload" is a differentiator, not just a compliance cost. Note that the commercial
tools (QXDM, XCAL, Nemo, TEMS) sidestep this by being on-premise — they never
upload, so they never had to solve it. Going cloud is precisely what creates the
obligation.

---

## 2. GDPR and equivalents

### 2a. Are the identifiers personal data?

Yes, with one nuance worth getting right.

- **GPS track**: personal data, uncontroversially. Art 29 WP Opinion 13/2011 on
  geolocation services on smart mobile devices held that even a MAC address
  combined with a WiFi access point location must be treated as personal data,
  because the controller cannot reliably distinguish identifiable from
  non-identifiable cases. Cellular measurement + GPS is an easier call than that.
- **IMSI / IMEI**: personal data. Stable, unique, tied to a subscription contract
  that an operator or authority can resolve to a named person.
- **TMSI / GUTI / C-RNTI**: **pseudonymous** personal data, not anonymous. GDPR
  Art 4(5) and Recital 26 are explicit that pseudonymised data remains personal
  data. The observed non-rotation (days, not minutes) removes the practical
  defence.
- **Cell ID / TAC alone**: not personal data. **Cell ID + timestamp + a device**:
  personal data, because it is a location record.

**Do not rely on hashing to escape this.** WP29 Opinion 05/2014 on Anonymisation
Techniques classifies hashing as *pseudonymisation*, not anonymisation; hashed
data stays personal data unless re-identification risk is insignificant. For an
IMSI that risk is not insignificant — an IMSI is 15 decimal digits, and the
realistic space is far smaller once MCC/MNC are known. Exhaustively hashing the
remaining ~10-digit MSIN is trivial on any GPU. A plain `SHA256(IMSI)` is a
**reversible** identifier, and shipping it would be worse than useless because it
would create a false sense of having solved the problem. See §5 for what to do
instead.

### 2b. Controller or processor?

The intuition "the customer uploads their own data, so we're just a processor" is
**probably wrong**, and this is the most commercially consequential question in
the document.

Under EDPB Guidelines 07/2020, the controller is whoever determines the *purposes
and means*. The concept is to be read broadly, and — importantly — "it is not
necessary that controllers have access to the personal data." The relevant
questions are: who decided what fields get captured? Who defined the schema? Who
set the retention period? Who decides what analytics run over it?

If FieldTap ships a consumer or self-serve product where you define the capture
set and the retention and offer analytics, **you are a controller** for the data
in those accounts, likely a **joint controller** with the customer where their
purposes and yours are, in the EDPB's phrase, complementary or inextricably
linked. Being a controller means you owe Art 13/14 transparency, Art 15-22 data
subject rights, Art 30 records, Art 35 DPIA, and you carry Art 82 liability.

Two distinct deployment shapes, two different answers, and the product should pick
one deliberately:

| Shape | Likely role | Consequence |
| --- | --- | --- |
| Enterprise: operator/NEM customer runs drive tests, defines their own capture profile, we host | Customer = controller, FieldTap = **processor** | Need Art 28 DPA, SCCs, sub-processor list. Cleanest. |
| Self-serve: individuals sign up, we set defaults and analyse | FieldTap = **controller** or joint controller | Full controller stack. Much heavier. |

There is a third party in the room that neither shape covers: **the paged
strangers**. For their TMSIs, FieldTap is a controller (or joint controller) of
personal data it can neither notify nor serve rights to. Art 14(5)(b) has a
"disproportionate effort" carve-out for indirectly collected data, but relying on
it to justify systematically ingesting third-party identifiers is a weak position
and I would not want to argue it. **The defensible answer is not to have the data.**

Also worth noting: the *user's* own capture of their own phone for personal use may
fall under the Art 2(2)(c) household exemption and be outside GDPR entirely. That
exemption evaporates the instant it is uploaded to your commercial service — the
household exemption never covers the receiving service.

### 2c. Lawful basis

- **Consent (Art 6(1)(a))** — the realistic basis for a self-serve product, and
  the one the industry uses. Must be freely given, specific, informed, unambiguous
  and as easy to withdraw as to give. WP29 13/2011 is blunt about geolocation
  specifically: consent "cannot be obtained through general terms and conditions",
  the default must be off, and withdrawal must carry no penalty. Burying it in a
  EULA checkbox fails.
- **Contract (Art 6(1)(b))** — plausible in enterprise, where measurement *is* the
  service the customer bought. Does not stretch to secondary analytics or
  benchmark-product resale.
- **Legitimate interests (Art 6(1)(f))** — requires a documented balancing test
  (LIA). Arguable for the user's own KPI data; **very hard to sustain for the
  paging records of uninvolved third parties**, who have no relationship with you
  and no reasonable expectation of being in your database. Do not plan on it.

Location is **not** special-category data under Art 9 — but note the divergence
from California below, where precise geolocation *is* elevated.

### 2d. ePrivacy Directive 2002/58 — the scope question, answered precisely

This is where the "does a measurement tool fall inside or outside" question has a
sharper answer than usual, because the Directive's articles have **different
addressees**.

**Article 6 (traffic data) and Article 9 (location data) bind "the provider" of a
public communications network or publicly available ECS.** FieldTap is not one.
So the Art 6 erase-or-anonymise duty and the Art 9 value-added-service consent
regime **do not directly bind a measurement tool**. Good news, and it is the part
people usually assume is the problem.

**Article 5(1) is different, and it is the one that bites.** Text:

> "…they shall prohibit listening, tapping, storage or other kinds of interception
> or surveillance of communications and the related traffic data **by persons other
> than users, without the consent of the users concerned**…"

Parse that against the two data classes:

- For the **user's own** signalling, the FieldTap user *is* a "user" (Art 2:
  "any natural person using a publicly available electronic communications
  service"). Art 5(1) is not engaged. Fine.
- For **another subscriber's paging record**, the FieldTap user is emphatically
  "a person other than [that] user", and does not have that user's consent. Art
  5(1) is engaged, and Art 5(1) is addressed to *everyone*, not just providers.

So the correct formulation for counsel is: **ePrivacy Art 6/9 are out of scope;
ePrivacy Art 5(1) is in scope, and only for the third-party portion of a diag
capture.** That is a narrow, fixable problem — which is the good news, because the
fix (drop foreign paging records on-device) is an engineering change, not a
business-model change.

*Uncertain:* the ePrivacy Regulation has been in negotiation for years and its
scope for terminal-equipment data has moved repeatedly. Anything built now should
assume Art 5(1)-equivalent obligations survive in some form. Confirm current status
with counsel — I have not verified the 2026 state of play.

### 2e. Data minimisation and retention

Art 5(1)(c) minimisation is the strongest argument *for* on-device redaction: if
the analysis only needs cell ID, RF metrics and position, then IMSI and foreign
TMSIs are by definition not "adequate, relevant and limited to what is necessary".
Collecting them is a minimisation breach independent of everything else in this
document.

Concrete retention anchor: **WP29 13/2011 recommended that unique identifiers in a
geolocation context be stored for a maximum of 24 hours** and then deleted or
anonymised. That is aggressive and is guidance, not law, but it is a defensible
number to cite in a retention policy and a useful default for raw uploads.

### 2f. CCPA/CPRA (California)

Materially stricter than GDPR on one axis:

- **"Precise geolocation" is Sensitive Personal Information** under Cal. Civ. Code
  § 1798.140(ae), defined in § 1798.140(w) as locating a consumer within a radius
  of **1,850 feet** (~564 m). A drive-test GPS track is far more precise than that.
- Consumers have a **right to limit** use of SPI (§ 1798.121) to what is necessary
  to provide the requested service. There is a carve-out where SPI is not used to
  infer characteristics — arguably applicable to network measurement — but it must
  be assessed, not assumed.
- SPI triggers notice-at-collection, a "Limit the Use of My Sensitive Personal
  Information" link, and purpose-limitation obligations.

Also relevant as a signal of how seriously US regulators treat this data class:
in April 2024 the **FCC fined AT&T ($57M), Verizon ($47M), Sprint ($12M) and
T-Mobile ($80M) — nearly $200M total — for sharing customer location data without
valid consent**, under § 222 of the Communications Act (CPNI). Not directly
applicable to a non-carrier, but it establishes that "location data from a mobile
network, shared onward without proper consent" is enforcement-active territory.

### 2g. India and other markets

- **India — DPDP Act 2023 + DPDP Rules 2025.** Rules notified 13 Nov 2025, phased
  in with most obligations landing ~18 months after publication. Notice-and-consent
  model; the "Data Fiduciary" (≈ controller) is responsible for processing done on
  its behalf **regardless of contract terms** — so the "we're only a processor"
  argument is weaker in India than in the EU. Government may restrict cross-border
  transfer of specified categories, and **traffic data is expressly mentioned** as
  something that can be designated non-exportable. If India is a target market,
  data residency is a live design constraint, not a later problem.
- **India — spectrum law.** Separately from privacy: the Indian Wireless
  Telegraphy Act 1933 and Indian Telegraph Act 1885 restrict possession and use of
  wireless telegraphy apparatus. This is mainly an SDR-capture concern rather than
  a handset-diag concern, but if the SDR route in `CAPTURE-OPTIONS.md` §3 is ever
  productised for India it needs its own check. **Not researched in depth — flag
  for counsel.**
- **Canada** — PIPEDA. CellMapper operates under it and publishes a policy on that
  basis, which is at least evidence the KPI-class model is workable there.
- **Brazil (LGPD), South Korea (PIPA), Japan (APPI)** — not researched. If they are
  target markets, they need their own pass.

---

## 3. The operator angle

### 3a. Do operators object? The honest answer: less than you would expect

I looked specifically for enforcement precedent — carrier cease-and-desist letters,
takedowns, or litigation against CellMapper, OpenCelliD or similar — and **found
none**. That is a genuine negative result, not a gap I am papering over. CellMapper
has operated publicly for years, publishes per-network statistics, and ships on
both app stores. OpenCelliD publishes its aggregate dataset under CC-BY-SA 4.0 and
is redistributed by AWS Marketplace and the World Bank Data Catalog. If operators
had a viable legal theory against crowdsourced tower mapping, a decade was enough
time to use it.

The likely reasons it has not been contested:

1. **Cell IDs are broadcast unencrypted to the world** in SIB1. They are the
   opposite of a trade secret; they are a public beacon. A confidentiality claim
   over data you transmit in the clear to every device in range is hard to run.
2. **Regulators actively want this data.** The FCC's broadband availability
   challenge process explicitly accepts drive-test and speed-test data submitted by
   consumers, state/local/Tribal governments and other third parties to contest
   carrier coverage claims. In the EU, the Open Internet Regulation (EU) 2015/2120
   and **EECC Art 104** direct NRAs to specify QoS parameters and measurement
   methods and contemplate certification mechanisms for measurement tools. A carrier
   arguing that independent measurement is unlawful would be arguing against its own
   regulator.
3. **Benchmarking is an established industry.** Opensignal and Tutela (both now
   under Comlinkdata) sell crowdsourced measurement to 140+ clients in 60+
   countries — and their customers are largely the operators themselves.

### 3b. Where operators *do* have a contractual hook

Not on topology. On **interception**, and it maps precisely onto the paging problem.

Verizon's Acceptable Use Policy prohibits subscribers from:

> "intercept, interfere with or redirect email or other transmissions sent by or to
> others"

and from "access without permission or right the accounts or computer systems of
others". Capturing paging records addressed to other subscribers is a plausible fit
for the first clause. Note it does *not* prohibit measuring your own service.

Verizon's and AT&T's wireless customer agreements also prohibit reverse
engineering, decompiling and disassembling software. Relevant to how diag access is
obtained rather than to what is measured:

- **Samsung `*#0808#`** is a vendor-provided service menu on a stock, unrooted
  device. Using a documented vendor menu is a much weaker "reverse engineering"
  target than patching a kernel would be, and it is a good reason to keep the
  no-root path as the flagship (this also aligns with the commercial argument
  already made in `CAPTURE-OPTIONS.md` §2).
- **Carrier-branded Verizon/AT&T units** have those codes disabled. Re-enabling
  them with a third-party tool is a materially worse position — arguably
  circumvention, and in the US potentially a **DMCA § 1201** question. Advise
  customers to buy carrier-unlocked; do not ship or endorse a code-re-enabling
  tool. **Not researched in depth — flag for counsel if it becomes a product path.**
- **Standalone modem modules** (Soracom Onyx, Quectel, Sierra) have no carrier
  customer agreement attached to the *device* at all, and the diag port is just a
  documented vendor USB endpoint. This is the cleanest position of the three and is
  another argument for the module-first plan.

### 3c. Adjacent risk: IP, not privacy

The Mozilla Location Service — the largest open crowdsourced WiFi/cell geolocation
database, 44M cell networks and 1.45bn WiFi networks — was **retired in June 2024**,
and Mozilla attributed the decline to a **patent issue dating from 2019** that made
improvement difficult. Not a privacy or operator action, but it is the one
documented case of a major crowdsourced radio-mapping service being killed by
legal exposure, and the exposure was **patents**. If FieldTap ever builds a
positioning database from uploaded cell observations, that is a distinct FTO
question. Out of scope here; worth logging.

### 3d. Net assessment

Collecting and centralising **network topology and performance** data is a
legitimate, regulator-endorsed activity with no visible enforcement precedent
against it. The operator risk is **contractual and relational**, not existential:
an operator can terminate a subscriber line, and can decline to buy from a vendor
it thinks is careless. The thing that would actually damage the relationship is not
mapping their cells — it is being the vendor that centralised their subscribers'
IMSIs.

---

## 4. Interception law

**Again: not legal advice. This section identifies questions for counsel.**

### 4a. Capturing your own device's signalling

**United States.** Two provisions matter and they point in different directions.

*The party exception.* 18 U.S.C. § 2511(2)(d): not unlawful for a person not acting
under colour of law to intercept "where such person is a party to the communication
or where one of the parties … has given prior consent", absent criminal or tortious
purpose. For signalling between the user's own UE and the network, the user has a
strong claim to be a party. **This covers the user's own traffic and nothing else.**

*The radio exception does NOT apply.* This is the finding most likely to surprise
people, and it is worth getting exactly right. § 2511(2)(g)(ii) permits intercepting
certain radio communications, but only where they are "readily accessible to the
general public". § 2510(16) defines that term for radio communications by
exclusion — a radio communication is **not** readily accessible to the general
public if it is:

> "(A) scrambled or encrypted; … (D) transmitted over a communication system
> provided by a common carrier, unless the communication is a tone only paging
> system communication…"

Subparagraph **(D) squarely excludes cellular**. A commercial mobile network is a
common-carrier communication system, and LTE/NR paging is not "tone only paging".
So the safe harbour for over-the-air radio interception is unavailable for cellular
regardless of the fact that the paging channel is unencrypted.

*The precedent that confirms this reasoning.* **Joffe v. Google, 746 F.3d 920
(9th Cir. 2013)** — the Street View WiFi payload case. Google argued its collection
of unencrypted WiFi payload data fell within the "readily accessible to the general
public" exemption. The Ninth Circuit rejected it, holding that WiFi payload data is
not a "radio communication" under § 2510(16), so that definition's exemption did
not apply; the Supreme Court denied cert, and Google settled for $13M. The lesson
generalises: **"it was unencrypted and in the air" is not a defence.** Courts have
declined to convert technical accessibility into legal permission. Cellular is in a
*worse* position than WiFi here, because § 2510(16)(D) excludes it expressly.

*A separate statute people forget.* **47 U.S.C. § 605(a)**: "No person not being
authorized by the sender shall intercept any radio communication and divulge or
publish the existence, contents, substance, purport, effect, or meaning of such
intercepted communication to any person." And a second-tier prohibition on anyone
who *receives* an intercepted radio communication **knowing it was intercepted** and
then divulges, publishes, or uses it. That second tier reaches **the cloud service,
not just the app** — which is a direct architectural warning.

*A third angle, under-discussed and genuinely unsettled.* Signalling identifiers
may not be "contents" for Wiretap Act purposes (§ 2510(8)), but they are squarely
"dialing, routing, addressing, or signaling information" under the pen/trap statute.
18 U.S.C. § 3121(a) prohibits installing or using a pen register or trap-and-trace
device without a court order; § 3127 defines a trap-and-trace device as "a device or
process which captures the incoming electronic or other impulses which identify …
[DRAS] information reasonably likely to identify the source" of a communication.
§ 3121(b)'s exception — including its consent branch — is drafted for "a provider of
electronic or wire communication service", which FieldTap is not. Penalty:
§ 3121(d), up to one year. **I want to be clear that I do not know how this comes
out.** There is a decent counter-argument that reading a log the modem generates for
its own operation is not "installing a device or process" to capture anything, and I
found no case applying § 3121 to handset diagnostics. But capturing *other people's*
paging identifiers is close enough to the statutory language that counsel should be
asked directly rather than the point being assumed away.

**United Kingdom.** Two statutes, and the second is the sharper one.

*IPA 2016 § 3* — offence to intentionally intercept a communication in the course of
its transmission by a public telecommunication system without lawful authority.
§ 6 defines lawful authority, which includes the consent of sender and recipient.
For the user's own traffic, consent-based lawful authority is arguable. For
strangers' paging, there is no consent from either end.

*Wireless Telegraphy Act 2006 § 48* — this is the provision that most directly
describes what a diag capture does, and I am quoting it in full because the
disclosure limb is the crux of the whole document:

> "(1) A person commits an offence if, without lawful authority—
> (a) he uses wireless telegraphy apparatus with intent to obtain information as to
> the contents, sender or addressee of a message (whether sent by means of wireless
> telegraphy or not) of which neither he nor a person on whose behalf he is acting
> is an intended recipient, or
> **(b) he discloses information as to the contents, sender or addressee of such a
> message.**"

Read (1)(a) against a paging record: it is a message whose **addressee** is another
subscriber, and the capture is apparatus used with intent to obtain information as
to that addressee. Read (1)(b): **disclosure is a separate offence limb.** § 48(2)
confirms the disclosure offence bites on information "that would not have come to
his knowledge but for the use of wireless telegraphy apparatus" — which is exactly
uploaded diag data. § 48(3A) defers to IPA § 3 where that applies instead. Penalty
is summary: a fine at level 5 on the standard scale (unlimited in England and Wales
since 2015). Ofcom publishes guidance on § 48 offences.

**Germany.** § 5 TDDDG (the renamed TTDSG; formerly § 89 TKG until Dec 2021):
with a radio facility, only messages intended for the operator of that facility, for
radio amateurs, for the general public, or for an indefinite group of persons may be
intercepted. Critically, the content of *other* messages **and the fact of their
reception may not be disclosed to anyone — even where the reception was
unintentional.** Criminal provisions sit at § 27 TDDDG (the predecessor § 148 TKG
carried up to two years' imprisonment or a fine).

Note the structure: Germany explicitly contemplates **accidental** reception and
still bans onward disclosure. That is the single best statutory illustration of the
point this whole document turns on. "We didn't mean to capture it" is expressly not
a defence to disclosing it.

**EU generally.** ePrivacy Art 5(1) as analysed in §2d, plus Member State criminal
law which varies. Germany is illustrative, not exceptional.

### 4b. Does uploading change the analysis?

**Yes. Unambiguously, and this is the most important legal finding in the document.**

Capture and disclosure are separately regulated in every jurisdiction examined:

| Jurisdiction | Capture | Disclosure / upload |
| --- | --- | --- |
| US | § 2511(2)(d) party exception may cover own traffic | **47 U.S.C. § 605(a)** — separate divulge/publish prohibition, and a second limb reaching the *recipient* of intercepted material |
| UK | WTA § 48(1)(a) — use of apparatus | **WTA § 48(1)(b)** — disclosure is its own offence limb |
| Germany | § 5 TDDDG reception ban | **§ 5 TDDDG disclosure ban, applying even to unintentional reception** |
| EU | ePrivacy Art 5(1) — interception | Art 5(1) covers "storage" too; plus GDPR obligations attach on receipt |

The pattern is consistent. Capturing your own device's signalling and keeping it
local is the low-risk end. **Transmitting it to a third-party server is the act the
statutes name specifically**, and for the user's own data that is largely cured by
consent, while for third-party paging records nothing cures it because those data
subjects cannot consent and do not know you exist.

### 4c. What I am genuinely uncertain about

Stated plainly, because false comfort here would be worse than useless:

1. **Whether signalling identifiers are "contents".** If they are not, the Wiretap
   Act may not reach them at all — and the pen/trap statute may reach them instead.
   I found no case law applying either statute to handset diagnostic logs. **Open.**
2. **Whether reading a modem's own diagnostic log is an "interception".** § 2510(4)
   requires "acquisition … through the use of any … device". The modem already
   decoded the paging message for its own operation; the diag port exposes a log of
   work already done. There is a real argument that no interception occurs, and a
   real counter-argument that the diag pipeline is the device. **Open, and it is
   the hinge on which the US analysis turns.**
3. **Whether § 3121 has ever been applied to a UE diag capture.** I found nothing.
   Absence of precedent is not safety.
4. **How the UK WTA § 48 "intended recipient" test treats a UE that is
   *architecturally required* to decode a shared paging PDU.** There is a decent
   argument that a UE is an intended recipient of the paging message *as a
   transmission* even where individual records address others. Not tested. **Open,
   and worth counsel's specific attention** — this is the argument that, if it
   holds, most reduces the UK capture-side risk. It does **not** help with the
   disclosure limb.
5. **ePrivacy Regulation status in 2026.** Not verified. Confirm.
6. **India spectrum law for SDR capture; DMCA § 1201 for re-enabling carrier-disabled
   diag menus.** Both flagged, neither researched.

None of these uncertainties affects the engineering conclusion, which is why §5 is
worth building regardless of how they resolve: **if the third-party identifiers
never leave the device, most of this analysis becomes moot.** That is the whole
design strategy — engineer the ambiguity out rather than litigate it.

---

## 5. Design requirements

Written as testable requirements. **R-x** = mandatory, **S-x** = strongly recommended.

### 5.1 Two classes, two pipelines

**R-1. KPI data and raw diag data MUST use separate upload paths, separate storage
buckets and separate consent.** They are different legal objects. Do not let a
"upload my logs" button conflate them.

**R-2. Default upload class MUST be KPI-only.** Raw diag upload is opt-in, per
session, with its own dialog — never a persisted global setting.

**S-3.** Consider making raw-diag upload an enterprise-tier feature gated behind a
signed DPA. It is a natural commercial boundary that happens to align with the
legal one.

### 5.2 On-device redaction — non-negotiable

**R-4. Redaction MUST happen on-device, before the bytes leave.** Server-side
redaction is not a mitigation: the unredacted data has already been transmitted,
already been received, and § 605's second limb and § 5 TDDDG's disclosure ban attach
on receipt. "We delete it after ingest" is a breach with a cleanup step.

**R-5. Foreign paging records MUST be dropped, not hashed.** Parse the PCCH
`PagingRecordList`; retain only the record matching the local UE's own
TMSI/GUTI/5G-S-TMSI; discard all others **before writing to disk**. Not before
upload — before writing to disk, so a crash dump or a support-bundle grab cannot
leak them either. This is the single highest-value change in the document.

**R-6. A capture that cannot be parsed MUST NOT be uploadable.** If the redactor
does not understand a message type, fail closed: drop the PDU and mark the session
as partially redacted. Never fall through to "upload raw because parsing failed" —
that inverts the safety property exactly when a new message format appears.

**R-7. Redaction table for the user's own identifiers:**

| Identifier | Treatment | Rationale |
| --- | --- | --- |
| IMSI / SUPI | **Drop.** Keep MCC/MNC only | Never needed for RF analysis |
| IMEI / IMEISV | **Drop.** Keep TAC (first 8 digits, = model) if device-model analytics are needed | TAC is not identifying; the full IMEI is |
| GUTI / 5G-GUTI | **Drop.** Keep AMF/MME identifier portion if core-node analysis is needed | The temporary-ID portion is the identifying part |
| Own TMSI / 5G-S-TMSI | Replace with per-session random token | Preserves intra-session correlation without cross-session linkage |
| C-RNTI | Keep | Cell-local, short-lived, needed for scheduling analysis |
| **Other subscribers' `ue-Identity`** | **Drop the record entirely (R-5)** | No lawful basis exists |
| SIB/MIB content | Keep | Broadcast, public, the actual product value |
| Measurement reports, RF metrics | Keep | The product |
| GPS | Keep, but see R-9 | Personal data; consented |
| NAS payload after security activation | Already ciphered; keep as opaque | Cannot be read anyway |

**R-8. If a pseudonymous token is needed, do NOT use a bare hash of the identifier.**
`SHA256(IMSI)` is brute-forceable in minutes. Use an HMAC with a per-account key
held only on-device, or a random token generated at session start with no
mathematical relationship to the source identifier. Prefer the latter — a key that
exists can be compelled or leaked.

### 5.3 Location

**R-9. Location precision MUST be configurable, and the upload MUST record which
precision was used.** Offer full precision (needed for real drive test),
~100 m truncation, and cell-centroid-only. This directly addresses CPRA's 1,850-foot
SPI threshold: a cell-centroid mode may fall outside "precise geolocation" entirely.

**R-10. Home/sensitive-area suppression.** Let a user define geofences where capture
is auto-suspended. Cheap to build, and it is the mitigation regulators and reviewers
ask about first.

### 5.4 Transport, storage, isolation

**R-11.** TLS 1.3, certificate pinning, no fallback below TLS 1.2. Given
`fieldSim` commit 6a2e085 dealt with re-signed TLS chains in corporate MITM
environments, decide **explicitly** whether upload traffic is pinned (breaks under
corporate MITM, which is the correct behaviour for this data) or trust-store-based.
Do not inherit that behaviour by accident.

**R-12.** Encryption at rest with per-tenant keys. Not one bucket key.

**R-13. Per-account isolation MUST be enforced at the storage layer**, not only in
application code — object-key prefixing plus IAM/row-level policy, so an
application bug cannot cross tenants.

**S-14.** Client-side encryption for raw diag, with the key held by the customer.
This makes FieldTap structurally unable to read enterprise captures, which is both
the strongest privacy position and a genuine sales feature against cloud-hosted
competitors.

**R-15.** Data residency selectable at account creation (EU / US / India), given the
DPDP cross-border provisions. Retrofitting residency is expensive; the account
schema should carry a region field from day one even if only one region ships.

### 5.5 Retention, export, deletion

**R-16.** Default retention for raw diag: **30 days**, then hard delete. Aggregates
and KPI derivatives may persist longer. Configurable down, and down only, per
account.

**S-17.** For any residual unredacted identifier, target the WP29 13/2011 **24-hour**
figure. If it survives 24 hours, justify it in writing.

**R-18.** Deletion MUST be real: object storage, database rows, search indices,
derived aggregates, **and backups** within the documented backup-rotation window.
Publish that window. "Deleted from the UI" is not deletion.

**R-19.** Self-service export in a documented open format (GDPR Art 20 / DPDP), and
self-service account deletion without a support ticket.

**R-20.** Audit log of every access to raw capture data, including access by
FieldTap staff. Retain the audit log longer than the data itself.

### 5.6 Consent

**R-21.** Consent captured **in-app**, versioned, timestamped, and stored with the
consent text as shown. When the text changes, re-consent. A record that says
"consented: true" without the text is not evidence.

**R-22.** Separate toggles for: (a) capture, (b) upload of KPI data, (c) upload of
raw diag, (d) any use of the data for anything beyond serving the user back their
own results. Bundled consent fails WP29 13/2011's "not through general terms and
conditions" test.

**R-23.** Withdrawal as easy as granting, in the same screen, with no penalty to
core functionality — the local capture must keep working when upload consent is
withdrawn.

**R-24.** Android: prominent in-app disclosure before the runtime permission
request, per Play's prominent-disclosure policy — in-app, not in the store listing,
not behind a settings menu, no auto-dismiss. Background location (needed for
continuous drive test) requires its own disclosure and a Play review declaration.

### 5.7 Governance

**R-25.** Run a **DPIA** (GDPR Art 35) before shipping raw-diag upload. Systematic
monitoring of a publicly accessible area at scale is on the EDPB's DPIA-mandatory
list, and this feature plausibly qualifies.

**S-26.** Document the paging-redaction design publicly. It is the only credible
answer to the first question a serious operator's security team will ask, and no
competitor has one.

**S-27.** Keep the existing `.gitignore` capture ban and `captures/README.md`
warning. The upload feature does not retire them — internal handling should stay
at least as strict as what is being asked of customers.

---

## 6. Open questions for counsel

1. Controller vs processor for each of the two deployment shapes in §2b — and what
   we are for the **paged third parties** in either shape.
2. Does the § 2511(2)(d) party exception reach signalling exchanged between the
   user's UE and the network? Is diag-log reading an "interception" at all
   (§ 4c item 2)?
3. Does 18 U.S.C. § 3121 reach a handset diag capture of foreign paging records?
4. Does 47 U.S.C. § 605's second limb create exposure for **us as recipient** of
   uploaded captures, independent of the user's conduct?
5. UK: is a UE an "intended recipient" of a shared paging PDU for WTA § 48 purposes?
   Does R-5 on-device redaction fully answer § 48(1)(b) disclosure?
6. Germany: does § 5 TDDDG's disclosure ban survive on-device redaction — i.e. is
   the redacted upload still "disclosure of the fact of reception"?
7. Current ePrivacy Regulation status and its treatment of terminal-equipment data.
8. India: DPDP data-residency obligations for traffic data; wireless-telegraphy
   licensing if SDR capture is productised.
9. DMCA § 1201 exposure for tooling that re-enables carrier-disabled diag menus.
10. Whether R-5 + R-7 redaction is sufficient to take uploaded captures outside
    "interception of another's communication" in each target jurisdiction — this is
    the question that determines whether the feature ships.

---

## 7. Sources

**Statutes and regulations**
- 18 U.S.C. § 2510 (definitions; § 2510(16) "readily accessible to the general public") — https://uscode.house.gov/view.xhtml?req=granuleid:USC-prelim-title18-section2510&num=0&edition=prelim
- 18 U.S.C. § 2511 (interception; § 2511(2)(d) party exception, § 2511(2)(g) radio) — https://uscode.house.gov/view.xhtml?req=granuleid%3AUSC-prelim-title18-section2511&num=0&edition=prelim
- 18 U.S.C. § 3121 (pen register / trap and trace prohibition) — https://uscode.house.gov/view.xhtml?req=granuleid:USC-prelim-title18-section3121&num=0&edition=prelim
- 18 U.S.C. § 3127 (definitions) — https://uscode.house.gov/view.xhtml?req=(title:18+section:3127+edition:prelim)
- 47 U.S.C. § 605 (unauthorized publication or use of communications) — https://uscode.house.gov/view.xhtml?req=%28title%3A47+section%3A605+edition%3Aprelim%29
- Wireless Telegraphy Act 2006 § 48 (UK) — https://www.legislation.gov.uk/ukpga/2006/36/section/48
- Ofcom, guidance on offences under WTA § 48 — https://www.ofcom.org.uk/__data/assets/pdf_file/0018/212580/offences-under-section-48-of-the-wireless-telegraphy-act-2006.pdf
- Investigatory Powers Act 2016 § 3 (UK) — https://www.legislation.gov.uk/ukpga/2016/25/section/3/enacted
- TDDDG (formerly TTDSG) § 5 Abhörverbot, § 27 Strafvorschriften (Germany) — https://www.gesetze-im-internet.de/ttdsg/
- Former TKG § 89 (Germany, to 30.11.2021) — https://dejure.org/gesetze/TKG_bis_30.11.2021/89.html
- Directive 2002/58/EC (ePrivacy), Arts 2, 3, 5(1), 6, 9, 15(1) — https://eur-lex.europa.eu/LexUriServ/LexUriServ.do?uri=CELEX:32002L0058:en:HTML
- Regulation (EU) 2015/2120 (Open Internet) — https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=CELEX:32015R2120
- Cal. Civ. Code § 1798.140(w), (ae), § 1798.121 (CPRA) — via BCLP, https://www.bclplaw.com/en-US/events-insights-news/precise-geolocation-recent-trends-and-enforcement.html
- India DPDP Act 2023 / DPDP Rules 2025 — https://www.privacyworld.blog/2025/11/india-passes-the-digital-personal-data-protection-rules-ushering-in-a-new-digital-age-in-india/

**Regulatory guidance**
- EDPB Guidelines 07/2020 on the concepts of controller and processor — https://www.edpb.europa.eu/system/files/documents/2023-10/EDPB_guidelines_202007_controllerprocessor_final_en.pdf
- Art 29 WP Opinion 13/2011 on geolocation services on smart mobile devices — https://www.huntonprivacyblog.com/2011/05/19/article-29-working-party-opines-on-geolocation-services/ and https://www.scl.org/2134-geolocation-consent-bombshell-from-article-29-wp/
- Art 29 WP Opinion 05/2014 on Anonymisation Techniques (hashing = pseudonymisation) — https://www.twobirds.com/en/insights/2019/global/big-data-and-issues-and-opportunities-anonymisation-pseudonymisation
- BEREC Guidelines on QoS parameters (EECC Art 104) — https://www.berec.europa.eu/system/files/2023-10/BoR%20(23)%20179%20Guidelines%20on%20QoS%20-for%20PC.pdf
- FCC, drive tests and speed tests to validate mobile broadband availability — https://www.fcc.gov/consumers/guides/drive-tests-and-other-speed-tests-conducted-validate-mobile-broadband-availability

**Case law and enforcement**
- Joffe v. Google, Inc., 746 F.3d 920 (9th Cir. 2013) — https://law.justia.com/cases/federal/appellate-courts/ca9/11-17483/11-17483-2013-09-10.html ; opinion PDF https://cdn.ca9.uscourts.gov/datastore/general/2013/09/11/11-17483_opinion.pdf ; Harvard L. Rev. note https://harvardlawreview.org/wp-content/uploads/2014/04/vol127_joffe_v_google.pdf
- FCC, ~$200M fines to AT&T/Sprint/T-Mobile/Verizon for location-data sharing (Apr 2024) — https://docs.fcc.gov/public/attachments/DOC-402213A1.pdf

**Technical — paging exposure**
- Sørseth, Zhou, Mjølsnes & Olimid, *Experimental Analysis of Subscribers' Privacy Exposure by LTE Paging*, arXiv:1807.11350 — https://arxiv.org/abs/1807.11350
- Hussain et al., *Privacy Attacks to the 4G and 5G Cellular Paging Protocols Using Side Channel Information*, NDSS 2019 (ToRPEDO, PIERCER, IMSI-Cracking) — https://www.ndss-symposium.org/wp-content/uploads/2019/02/ndss2019_05B-5_Hussain_paper.pdf
- Shaik et al., *Practical Attacks Against Privacy and Availability in 4G/LTE*, NDSS 2016 — https://arxiv.org/pdf/1510.07563
- Borgaonkar et al., *LTE and IMSI Catcher Myths*, Black Hat EU 2015 — https://blackhat.com/docs/eu-15/materials/eu-15-Borgaonkar-LTE-And-IMSI-Catcher-Myths-wp.pdf
- LTE paging: max 16 paging records per message, shared paging occasions — https://www.rfwireless-world.com/tutorials/lte-paging-procedure-mechanism-call-flow
- Wireshark GSMTAP dissector (LTE-RRC channel types incl. PCCH) — https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-gsmtap.c

**Tooling and industry practice**
- QCSuper README (no privacy/redaction discussion) — https://github.com/P1sec/QCSuper/blob/master/README.md
- P1 Security, QCSuper announcement — https://labs.p1sec.com/2019/07/09/presenting-qcsuper-a-tool-for-capturing-your-2g-3g-4g-air-traffic-on-qualcomm-based-phones/
- Opensignal Data Privacy Charter — https://www.opensignal.com/apps/data-privacy-charter
- Opensignal privacy policy (apps) — https://www.opensignal.com/privacy-policy-apps-connectivity-assistant
- CellMapper privacy policy — https://www.cellmapper.net/privacy and https://www.cellmapper.net/android_privacy
- OpenCelliD — https://www.opencellid.org/
- Mozilla Location Service retirement (patent issue) — https://en.wikipedia.org/wiki/Mozilla_Location_Service and https://www.omgubuntu.co.uk/2024/03/mozilla-location-services-axed
- Verizon Acceptable Use Policy — https://www.verizon.com/about/terms-conditions/acceptable-use-policy
- Verizon Mobile Customer Agreement — https://www.verizon.com/support/customer-agreement/
- AT&T Wireless Customer Agreement — https://att.com/legal/terms.wirelessCustomerAgreement.html
- Android 10 privacy changes: non-resettable identifiers — https://developer.android.com/about/versions/10/privacy/changes
- AOSP device identifiers — https://source.android.com/docs/core/device-identifiers
- Google Play, prominent disclosure and consent — https://support.google.com/googleplay/android-developer/answer/11150561
- Google Play, background location permissions — https://support.google.com/googleplay/android-developer/answer/9799150
