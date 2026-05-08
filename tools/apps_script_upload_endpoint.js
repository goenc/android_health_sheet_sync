const SPREADSHEET_ID = '1QXipvEOmwPek9fz9yHs4DTNArVsOwUC0';

function doPost(e) {
  const payload = JSON.parse(e.postData.contents);
  if (payload.spreadsheetId !== SPREADSHEET_ID) {
    throw new Error('Unexpected spreadsheetId');
  }

  const spreadsheet = SpreadsheetApp.openById(SPREADSHEET_ID);
  appendObjects(spreadsheet, 'weightRecords', [
    'measuredAt',
    'targetDate',
    'timeBand',
    'weightKg',
    'healthConnectId',
    'sourceAppName',
    'sourcePackageName',
  ], payload.weightRecords || []);
  appendObjects(spreadsheet, 'glucoseRecords', [
    'measuredAt',
    'targetDate',
    'timeBand',
    'bloodGlucoseMgDl',
    'mealRelation',
    'healthConnectId',
    'sourceAppName',
    'sourcePackageName',
  ], payload.glucoseRecords || []);
  appendObjects(spreadsheet, 'stepDailyRecords', [
    'targetDate',
    'steps',
    'aggregationStartAt',
    'aggregationEndAt',
  ], payload.stepDailyRecords || []);

  return ContentService
    .createTextOutput(JSON.stringify({ status: 'ok' }))
    .setMimeType(ContentService.MimeType.JSON);
}

function appendObjects(spreadsheet, sheetName, headers, records) {
  if (records.length === 0) {
    return;
  }

  const sheet = spreadsheet.getSheetByName(sheetName) || spreadsheet.insertSheet(sheetName);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(headers);
  }

  const rows = records.map(record => headers.map(header => record[header] ?? ''));
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, headers.length).setValues(rows);
}
