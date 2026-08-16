import sys, ezdxf
from collections import Counter
path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Administrator\Pictures\New folder\Yarmouk_Camp_Shelter_Android\build_debug\validate.dxf"
doc = ezdxf.readfile(path)              # strict loader — throws on structural problems
aud = doc.audit()
msp = doc.modelspace()
print("dxfversion =", doc.dxfversion, " codepage =", doc.header.get('$DWGCODEPAGE'))
print("layers =", sorted(l.dxf.name for l in doc.layers))
print("styles =", sorted(s.dxf.name for s in doc.styles), " font =", doc.styles.get('STANDARD').dxf.font)
print("entities =", dict(Counter(e.dxftype() for e in msp)))
texts = [e.dxf.text for e in msp if e.dxftype() == "TEXT"]
arabic = [t for t in texts if any('\u0600' <= ch <= '\u06FF' for ch in t)]
print("arabic room names =", arabic)
print("audit errors =", len(aud.errors), " fixes =", len(aud.fixes))
for e in aud.errors[:10]:
    print("  ERR:", e)
print("VALID_CAD_FILE" if not aud.errors else "AUDIT_ERRORS")
