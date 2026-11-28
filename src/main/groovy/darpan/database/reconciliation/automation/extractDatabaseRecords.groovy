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
    if (queryRow?.companyUserGroupId?.toString()?.trim() != companyUserGroupIdValue) {
        ec.message.addError("Database source query ${queryIdValue} not found.")
    }
} else {
    TenantAccessSupport.requireTenantRecordAccess(
            ec, queryRow,
            "Database source query ${queryIdValue} not found.")
}

def configRow = null
if (queryRow && !ec.message.hasError()) {
    configRow = ec.entity.find("darpan.database.DatabaseSourceConfig")
            .condition("databaseSourceConfigId", queryRow.databaseSourceConfigId)
            .disableAuthz().useCache(false).one()
    // Fails closed on a missing connection, a blank tenant on either side, or two tenants.
    String mismatch = DatabaseExtractionSupport.queryConfigMismatch(queryRow, configRow)
    if (mismatch) ec.message.addError(mismatch)
}
if (queryRow && (queryRow.isActive ?: "Y").toString().equalsIgnoreCase("N")) ec.message.addError("Database source query ${queryIdValue} is inactive.")
if (configRow && (configRow.isActive ?: "Y").toString().equalsIgnoreCase("N")) ec.message.addError("Database connection ${configRow.databaseSourceConfigId} is inactive.")

if (ec.message.hasError()) {
    // Mirror extractOmsOrders.groovy exactly: copy the accumulated ec.message errors into the
    // `errors` out-parameter and DO NOT clear them (OMS, the proven-in-production reference, does
    // not clear — see extractOmsOrders.groovy:40-46). The service facade rolls the transaction back
    // on ec.message.hasError() and the caller reads failures from `errors`.
    //
    // The copy is deliberate: MessageFacadeImpl.getErrors() returns Collections.unmodifiableList(
    // errorList) — a LIVE VIEW over the backing list, not a snapshot. new ArrayList<>(...) captures
    // an independent copy so the out-parameter is stable even if the shared MessageFacade is later
    // mutated (e.g. cleared by the caller between calls).
    errors = new ArrayList<String>(ec.message.getErrors())
    warnings = []
    dataAvailable = false
    recordCount = 0
    fileLocation = null
    fileName = null
    return
}

Map extraction = DatabaseExtractionSupport.runExtraction(
        new HashMap(configRow), new HashMap(queryRow), windowStart, windowEnd)
// DAR-BE-386: a read by key (a pulse asking which orders the target holds, an audit re-checking its open findings)
// narrows the query's own window to those keys.
if (lookupKeys != null) {
    try {
        extraction = DatabaseExtractionSupport.filterExtraction(extraction, lookupKeys as Collection, lookupKeyField?.toString()?.trim())
    } catch (IllegalArgumentException e) {
        extraction = extraction + [errors: [e.message], dataAvailable: false, outputText: null, recordCount: 0L]
    }
}
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
