import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const input = await FileBlob.load("F:/Project_Data17/Reference.xlsx");
const workbook = await SpreadsheetFile.importXlsx(input);
const sheet = workbook.worksheets.getItem("الكميات");
const values = sheet.getRange("B7:J43").values;

let category = "";
const items = values.map((row) => {
  const currentCategory = String(row[1] ?? "").trim();
  if (currentCategory) category = currentCategory;
  return {
    number: Number(row[0]),
    category,
    name: String(row[2] ?? "").trim(),
    unit: String(row[3] ?? "").trim(),
    unitPrice: Number(row[5]),
    description: String(row[7] ?? "").trim(),
    reason: String(row[8] ?? "").trim(),
  };
});

if (items.length !== 37 || items.some((item, index) => item.number !== index + 1)) {
  throw new Error("Unexpected BOQ row structure");
}

const catalog = {
  source: "Reference.xlsx",
  sheet: "الكميات",
  sourceRange: "B7:J43",
  currency: "USD",
  firstPaymentRatio: 0.6,
  secondPaymentRatio: 0.4,
  items,
};

await fs.writeFile("boq_reference.extracted.json", JSON.stringify(catalog, null, 2), "utf8");
console.log(JSON.stringify({ count: items.length, categories: [...new Set(items.map((item) => item.category))], totalIfQuantityOne: items.reduce((sum, item) => sum + item.unitPrice, 0) }, null, 2));
