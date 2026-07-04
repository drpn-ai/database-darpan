import darpan.database.DatabaseExtractionSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport

String queryIdValue = databaseSourceQueryId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
if (!queryIdValue) {
    errors = ["Database Source Query ID is required."]
    warnings = []
    dataAvailable = false
    recordCount = 0
    return
}

def queryRow = ec.entity.find("darpan.database.DatabaseSourceQuery")
        .condition("databaseSourceQueryId", queryIdValue)
        .disableAuthz().useCache(false).one()

if (companyUserGroupIdValue) {
    if (!queryRow) {
        ec.message.addError("Database source query ${queryIdValue} not found.")
    } else if (queryRow.companyUserGroupId?.toString()?.trim() != companyUserGroupIdValue) {
        ec.message.addError("Database source query ${queryIdValue} is not available in this automation tenant.")
    }
} else {
    TenantAccessSupport.requireTenantRecordAccess(
            ec, queryRow,
            "Database source query ${queryIdValue} not found.",
            "Database source query ${queryIdValue} is not available in your active tenant.")
}

def configRow = null
if (queryRow && !ec.message.hasError()) {
    configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("databaseSourceConfigId", queryRow.databaseSourceConfigId)
            .disableAuthz().useCache(false).one()
    if (!configRow) ec.message.addError("Database connection ${queryRow.databaseSourceConfigId} not found.")
    else if (configRow.companyUserGroupId?.toString()?.trim() != queryRow.companyUserGroupId?.toString()?.trim())
        ec.message.addError("Database connection and query belong to different tenants.")
}
if (queryRow && (queryRow.isActive ?: "Y").toString().equalsIgnoreCase("N")) ec.message.addError("Database source query ${queryIdValue} is inactive.")
if (configRow && (configRow.isActive ?: "Y").toString().equalsIgnoreCase("N")) ec.message.addError("Database connection ${configRow.databaseSourceConfigId} is inactive.")

if (ec.message.hasError()) {
    // Deviation from extractOmsOrders.groovy: that script leaves the errors on ec.message after
    // copying them into the out-parameter. This script clears them because the service-facade
    // contract (task brief critical intent #2) is "errors via out-parameters, not thrown" — and
    // ServiceCallSyncImpl#callSingle refuses to even run a *subsequent* service call while
    // ec.message.hasError() is still true (it bails out before the script runs at all). Leaving
    // stale errors on a shared ExecutionContext (e.g. an automation runner processing several
    // DatabaseSourceQuery rows in one execution, or this component's own smoke-test suite sharing
    // one ec across @Test methods) would silently block unrelated later extractions. Clearing here
    // keeps each extract#DatabaseRecords call independent, matching the intended out-parameter-only
    // error contract without changing what the caller observes from THIS call.
    //
    // IMPORTANT: MessageFacadeImpl.getErrors() returns Collections.unmodifiableList(errorList) — a
    // LIVE VIEW over the same backing list, not a copy. clearErrors() calls errorList.clear() on
    // that same backing list, which would silently empty this out-parameter too if we kept a
    // reference to the view instead of copying the elements first. Copy with `new ArrayList<>(...)`
    // before clearing.
    errors = new ArrayList<String>(ec.message.getErrors())
    ec.message.clearErrors()
    warnings = []
    dataAvailable = false
    recordCount = 0
    fileLocation = null
    fileName = null
    return
}

Map extraction = DatabaseExtractionSupport.runExtraction(
        new HashMap(configRow), new HashMap(queryRow), windowStart, windowEnd)
warnings = extraction.warnings ?: []
errors = extraction.errors ?: []
requestMetadata = extraction.requestMetadata ?: [:]
recordCount = (extraction.recordCount ?: 0L) as Integer
dataAvailable = extraction.dataAvailable == true

if (errors || !dataAvailable) {
    fileLocation = null
    fileName = null
    return
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec, automationExecutionId ?: queryIdValue, timestamp)
String outputFileName = (fileName ?: "database-records-${timestamp}.json").toString().replaceAll(/[^A-Za-z0-9._-]/, "_")
fileName = outputFileName
fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
DataManagerSupport.writeText(ec, fileLocation as String, extraction.outputText)
