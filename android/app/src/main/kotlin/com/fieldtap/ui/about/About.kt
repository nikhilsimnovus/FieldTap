package com.fieldtap.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.fieldtap.R
import com.fieldtap.app.AppInfo
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.KeyValueRow
import com.fieldtap.ui.components.LimitsStatementCard
import com.fieldtap.ui.components.PreviewSurface
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.StatusChip
import com.fieldtap.ui.setup.SetupParagraph
import com.fieldtap.ui.setup.SetupScreenScaffold
import com.fieldtap.ui.setup.setupContentWidth
import com.fieldtap.ui.theme.FieldTapBrandMark
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * About: the app name, the limits statement word for word (`R.string.limits_statement`), version name and
 * code, publisher 5gto6g, "free", and that there is no account yet (`R.string.about_no_account`).
 *
 * It says plainly that an account is coming, and adds the privacy promises and the open-source notice. Everything
 * shown comes from [appInfo] and string resources, so the screen needs no view model.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun AboutScreen(
    appInfo: AppInfo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleText = stringResource(R.string.about_title)
    val appName = stringResource(R.string.app_name)
    val versionText = stringResource(R.string.about_version, appInfo.versionName, appInfo.versionCode)
    val debugText = stringResource(R.string.about_debug_build)
    val accountTitle = stringResource(R.string.about_account_title)
    val noAccountText = stringResource(R.string.about_no_account)
    val limitsTitle = stringResource(R.string.about_limits_title)
    val limitsStatement = stringResource(R.string.limits_statement)
    val detailsTitle = stringResource(R.string.about_details_title)
    val publisherKey = stringResource(R.string.about_publisher_key)
    val publisher = stringResource(R.string.about_publisher)
    val priceKey = stringResource(R.string.about_price_key)
    val price = stringResource(R.string.about_price)
    val applicationIdKey = stringResource(R.string.about_application_id_key)
    val privacyTitle = stringResource(R.string.about_privacy_title)
    val privacyBody = stringResource(R.string.about_privacy_body)
    val ossTitle = stringResource(R.string.about_oss_title)
    val ossBody = stringResource(R.string.about_oss_body)

    SetupScreenScaffold(title = titleText, modifier = modifier, onBack = onBack) {
        item(key = "header") {
            Column(
                modifier = Modifier
                    .setupContentWidth()
                    .padding(vertical = Spacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                FieldTapBrandMark(modifier = Modifier.size(Sizes.EmptyStateBadge))
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = versionText,
                    style = FieldTapDesign.numeric.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (appInfo.debuggable) {
                    StatusChip(text = debugText, tone = StatusTone.WARNING, icon = FieldTapIcons.Info)
                }
            }
        }
        item(key = "account") {
            // A standing fact, not a state of measuring: a card, not a banner.
            SectionCard(title = accountTitle, icon = FieldTapIcons.Info, modifier = Modifier.setupContentWidth()) {
                SetupParagraph(text = noAccountText)
            }
        }
        item(key = "limits") {
            LimitsStatementCard(title = limitsTitle, statement = limitsStatement, modifier = Modifier.setupContentWidth())
        }
        item(key = "details") {
            SectionCard(title = detailsTitle, modifier = Modifier.setupContentWidth()) {
                KeyValueRow(key = publisherKey, value = publisher, tabular = false)
                KeyValueRow(key = priceKey, value = price, tabular = false)
                KeyValueRow(key = applicationIdKey, value = appInfo.applicationId, tabular = false, stacked = true, selectable = true)
            }
        }
        item(key = "privacy") {
            SectionCard(title = privacyTitle, icon = FieldTapIcons.Shield, modifier = Modifier.setupContentWidth()) {
                SetupParagraph(text = privacyBody)
            }
        }
        item(key = "open-source") {
            SectionCard(title = ossTitle, icon = FieldTapIcons.File, modifier = Modifier.setupContentWidth()) {
                SetupParagraph(text = ossBody)
            }
        }
    }
}

@FieldTapPreviews
@Composable
private fun AboutPreview() {
    PreviewSurface {
        AboutScreen(
            appInfo = AppInfo(versionName = "0.1.0", versionCode = 1, applicationId = "com.fieldtap", debuggable = true),
            onBack = {},
        )
    }
}
