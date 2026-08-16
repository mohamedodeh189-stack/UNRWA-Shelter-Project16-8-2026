import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "F:/Project_Data17/Reference.xlsx";
const outputDir = path.resolve(".");

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const summary = await workbook.inspect({
  kind: "workbook,sheet,table,definedName,drawing",
  maxChars: 20000,
  tableMaxRows: 10,
  tableMaxCols: 12,
  tableMaxCellChars: 120,
});
await fs.writeFile(path.join(outputDir, "workbook_summary.ndjson"), summary.ndjson, "utf8");

const sheetList = await workbook.inspect({
  kind: "sheet",
  include: "id,name",
  maxChars: 20000,
});
await fs.writeFile(path.join(outputDir, "sheet_list.ndjson"), sheetList.ndjson, "utf8");

const sheets = [];
for (let index = 0; index < workbook.worksheets.items.length; index += 1) {
  const sheet = workbook.worksheets.getItemAt(index);
  const used = sheet.getUsedRange();
  const name = sheet.name;
  const safeName = name.replace(/[<>:"/\\|?*]+/g, "_");
  const region = await workbook.inspect({
    kind: "region",
    sheetId: name,
    range: used.address,
    maxChars: 120000,
    tableMaxRows: 300,
    tableMaxCols: 30,
    tableMaxCellChars: 300,
  });
  await fs.writeFile(path.join(outputDir, `${String(index + 1).padStart(2, "0")}_${safeName}_region.ndjson`), region.ndjson, "utf8");

  const formulas = await workbook.inspect({
    kind: "formula",
    sheetId: name,
    range: used.address,
    maxChars: 80000,
    options: { maxResults: 1000 },
  });
  await fs.writeFile(path.join(outputDir, `${String(index + 1).padStart(2, "0")}_${safeName}_formulas.ndjson`), formulas.ndjson, "utf8");

  const previewName = `${String(index + 1).padStart(2, "0")}_${safeName}.png`;
  let renderError = null;
  try {
    const preview = await workbook.render({
      sheetName: name,
      autoCrop: "all",
      scale: 1.25,
      format: "png",
    });
    const previewBytes = new Uint8Array(await preview.arrayBuffer());
    await fs.writeFile(path.join(outputDir, previewName), previewBytes);
  } catch (error) {
    renderError = String(error?.message ?? error);
  }

  sheets.push({ index, name, usedRange: used.address, preview: previewName, renderError });
}

await fs.writeFile(path.join(outputDir, "sheets.json"), JSON.stringify(sheets, null, 2), "utf8");
console.log(JSON.stringify({ inputPath, sheets }, null, 2));
