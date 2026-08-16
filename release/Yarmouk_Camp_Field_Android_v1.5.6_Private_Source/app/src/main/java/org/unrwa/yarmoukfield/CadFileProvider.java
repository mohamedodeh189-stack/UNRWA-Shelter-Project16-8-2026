package org.unrwa.yarmoukfield;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider used to grant an external CAD viewer access to embedded DWG/DXF files. */
public final class CadFileProvider extends ContentProvider {
    public static Uri uriFor(android.content.Context context,File file){
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".files").appendPath("embedded_cad").appendPath(file.getName()).build();
    }

    @Override public boolean onCreate(){return true;}

    private File resolve(Uri uri)throws FileNotFoundException{
        String name=uri.getLastPathSegment();if(name==null||name.isEmpty())throw new FileNotFoundException("Missing CAD file name");
        try{File root=new File(getContext().getCacheDir(),"embedded_cad").getCanonicalFile();File file=new File(root,name).getCanonicalFile();if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.isFile())throw new FileNotFoundException(name);return file;}catch(FileNotFoundException error){throw error;}catch(Exception error){throw new FileNotFoundException(error.getMessage());}
    }

    @Override public String getType(Uri uri){String name=String.valueOf(uri.getLastPathSegment()).toLowerCase();return name.endsWith(".dwg")?"application/vnd.dwg":name.endsWith(".dxf")?"application/dxf":name.endsWith(".ttf")?"font/ttf":"application/octet-stream";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[]projection,String selection,String[]selectionArgs,String sortOrder){try{File file=resolve(uri);String[]columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns){if(OpenableColumns.DISPLAY_NAME.equals(column))row.add(file.getName());else if(OpenableColumns.SIZE.equals(column))row.add(file.length());else row.add(null);}return cursor;}catch(FileNotFoundException error){throw new IllegalArgumentException(error);}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[]selectionArgs){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[]selectionArgs){throw new UnsupportedOperationException("Read only");}
}
