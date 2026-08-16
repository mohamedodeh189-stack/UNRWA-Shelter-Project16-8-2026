package org.unrwa.yarmoukfield.drawing;

import java.util.ArrayList;
import java.util.List;

/** A drawing layer: a stable id, an Arabic display name (in-app/PDF), a Latin DXF layer name, and a
 * show/hide flag. Layer set is fixed for the MVP. Pure data, no Android dependency. */
public final class DrawLayer {
    public String id;      // stable key, e.g. "walls"
    public String nameAr;  // Arabic display name
    public String dxfName; // Latin-safe DXF layer name, e.g. "WALLS"
    public boolean visible = true;

    public DrawLayer() {}
    public DrawLayer(String id, String nameAr, String dxfName, boolean visible) {
        this.id = id; this.nameAr = nameAr; this.dxfName = dxfName; this.visible = visible;
    }

    public DrawLayer copy() { return new DrawLayer(id, nameAr, dxfName, visible); }

    /** The default MVP layer stack, in draw order. */
    public static List<DrawLayer> defaults() {
        List<DrawLayer> l = new ArrayList<>();
        l.add(new DrawLayer("walls", "الجدران", "Walls", true));
        l.add(new DrawLayer("doors", "الأبواب", "Doors", true));
        l.add(new DrawLayer("windows", "النوافذ", "Windows", true));
        l.add(new DrawLayer("rooms", "حدود الغرف", "Rooms", true));
        l.add(new DrawLayer("roomnames", "أسماء الغرف", "RoomNames", true));
        l.add(new DrawLayer("dims", "الأبعاد", "Dimensions", true));
        l.add(new DrawLayer("notes", "الملاحظات", "Notes", true));
        l.add(new DrawLayer("damage", "الأضرار", "Damage", true));
        return l;
    }
}
