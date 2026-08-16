import json
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "boq_reference.json"


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    items = catalog["items"]
    assert len(items) == 37
    assert [item["number"] for item in items] == list(range(1, 38))
    prices = {item["number"]: item["unitPrice"] for item in items}

    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY, amount TEXT, progress_percent INTEGER DEFAULT 0)")
    db.execute("CREATE TABLE beneficiary_quantities (_id INTEGER PRIMARY KEY, beneficiary_id INTEGER NOT NULL, item_no INTEGER NOT NULL, quantity REAL NOT NULL, unit_price REAL NOT NULL, FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE, UNIQUE(beneficiary_id,item_no))")
    db.execute("INSERT INTO beneficiaries(_id,amount) VALUES(1,'')")
    # 12 m2 ceramic (item 10) and 5 m2 block (item 3).
    db.execute("INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES(1,10,12,?)", (prices[10],))
    db.execute("INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES(1,3,5,?)", (prices[3],))
    total = db.execute("SELECT SUM(quantity*unit_price) FROM beneficiary_quantities WHERE beneficiary_id=1").fetchone()[0]
    assert total == 375
    assert total * catalog["firstPaymentRatio"] == 225
    assert total * catalog["secondPaymentRatio"] == 150
    db.execute("UPDATE beneficiaries SET progress_percent=100 WHERE _id=1")
    assert db.execute("SELECT progress_percent FROM beneficiaries WHERE _id=1").fetchone()[0] == 100
    print("BOQ_WORKFLOW_OK|items=37|total=375|first=225|second=150|progress=100")


if __name__ == "__main__":
    main()
