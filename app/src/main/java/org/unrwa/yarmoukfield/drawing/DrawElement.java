package org.unrwa.yarmoukfield.drawing;

/** One drawable element in a house sketch. Pure data + a deep copy — zero Android dependency so the whole
 * geometry/model layer is verifiable on a plain JDK. Coordinates are in neutral WORLD units (equal to
 * on-screen pixels at zoom=1); {@link DrawModel#pxPerMeter} converts world lengths to real meters after
 * calibration. This is the MVP element set (walls/lines/rooms/labels); architectural symbols and BOQ links
 * are deferred per the module spec. */
public final class DrawElement {
    public static final String WALL = "wall";   // pts = [x1,y1,x2,y2]
    public static final String LINE = "line";   // pts = [x1,y1,x2,y2]
    public static final String ROOM = "room";   // pts = [x1,y1,x2,y2] (two opposite corners)
    public static final String LABEL = "label";  // pts = [x,y]
    public static final String DOOR = "door";   // A2: anchored to a WALL by hostId + t; pts = [cx,cy] (cached centre)
    public static final String WINDOW = "window"; // A3: anchored to a WALL by hostId + t like a door (no swing)
    public static final String STAIRS = "stairs"; // simple stairs: pts = [x1,y1,x2,y2] rectangle; swing&1 flips ascent

    public String type;
    public String layer;       // layer id, e.g. "walls"
    public double[] pts;       // world coordinates (see per-type shapes above)
    public String label = "";  // room name in Arabic (ROOM/LABEL); "" otherwise
    public String code = "";   // Latin-safe DXF code (e.g. "R1") for ROOM; "" otherwise
    public boolean damaged = false; // MVP: simple damage flag (BOQ link deferred)
    public String damageNote = "";
    /** A2 (doors): a stable id so a DOOR can stay attached to its host WALL across moves/edits. Assigned on add. */
    public String id = "";
    /** A2 (doors): id of the WALL this door sits on. The door's geometry is derived from the host each render/
     * export, so moving the wall moves the door. "" for non-door elements. */
    public String hostId = "";
    /** A2 (doors): the door centre's parametric position along the host wall axis, 0..1. */
    public double t = 0;
    /** A2 (doors): door leaf width in metres. 0 = unset → shown at the model default door width. */
    public double widthM = 0;
    /** A2 (doors): opening direction 0..3 = (side bit &lt;&lt;1 | hinge-end bit). Cycled by tapping the door. */
    public int swing = 0;
    /** A2 (doors): door subtype — "" / "داخلي" / "خارجي" (hinged, drawn with a swing arc) or "أكورديون" / "مطوي"
     * (folding, drawn as a zigzag). Empty = a plain hinged door. */
    public String kind = "";
    /** WALL thickness in metres. 0 = UNSET — an older wall (or a wall drawn before this feature) keeps 0 and is
     * only DISPLAYED at the default thickness; it is never auto-rewritten. It becomes a real stored value only
     * when the engineer picks a thickness for it. The wall's {@link #pts} stay the axis, so a future "net inner
     * room dimension" mode can be derived from axis + thickness without any data change here. */
    public double thicknessM = 0;

    public DrawElement() {}

    public DrawElement(String type, String layer, double[] pts) {
        this.type = type; this.layer = layer; this.pts = pts;
    }

    public DrawElement copy() {
        DrawElement e = new DrawElement(type, layer, pts == null ? null : pts.clone());
        e.label = label; e.code = code; e.damaged = damaged; e.damageNote = damageNote; e.thicknessM = thicknessM;
        e.id = id; e.hostId = hostId; e.t = t; e.widthM = widthM; e.swing = swing;
        return e;
    }
}
