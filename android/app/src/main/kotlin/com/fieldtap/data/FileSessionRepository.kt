package com.fieldtap.data

import com.fieldtap.app.SessionDetail
import com.fieldtap.app.SessionRepository
import com.fieldtap.app.SessionSummary
import com.fieldtap.core.export.ExportResult
import com.fieldtap.core.export.SessionExporter
import com.fieldtap.core.session.SessionPaths
import com.fieldtap.core.session.SessionStore
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.format.LocationPrecision
import java.io.File

/**
 * [SessionRepository] over the session directories, on `Dispatchers.IO`.
 * [export] deletes older zips in [exportDir] first, so shared copies do not pile up.
 *
 * Owner: workstream `service-and-tests`.
 */
class FileSessionRepository(
    private val paths: SessionPaths,
    private val store: SessionStore,
    private val exporter: SessionExporter,
    private val storagePolicy: StoragePolicy,
    private val exportDir: File,
    private val activeDirName: () -> String?,
) : SessionRepository {
    override suspend fun list(): List<SessionSummary> = TODO("service-and-tests")

    override suspend fun detail(dirName: String): SessionDetail? = TODO("service-and-tests")

    override suspend fun delete(dirName: String): Boolean = TODO("service-and-tests")

    override suspend fun export(dirName: String, precision: LocationPrecision): ExportResult =
        TODO("service-and-tests")

    override suspend fun storage(): StorageStatus = TODO("service-and-tests")
}
