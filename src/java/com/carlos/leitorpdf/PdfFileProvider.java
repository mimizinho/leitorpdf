package com.carlos.leitorpdf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Provider próprio e minimalista pra expor PDFs guardados em
 * getCacheDir()/shared_pdfs/ como content:// URI, permitindo compartilhar
 * com outros apps (câmera de e-mail, WhatsApp, etc). Existe pra substituir
 * a androidx.core.content.FileProvider, que não está disponível neste
 * projeto (build sem Gradle, sem resolução de dependências Maven).
 *
 * Só serve arquivos dentro da subpasta "shared_pdfs" do cache do próprio
 * app — nada além disso é exposto.
 */
public class PdfFileProvider extends ContentProvider {

    private static final String SHARED_DIR = "shared_pdfs";

    private File resolveFile(Uri uri) {
        String path = uri.getPath(); // ex: /shared_pdfs/documento.pdf
        if (path == null) return null;
        File base = new File(getContext().getCacheDir(), SHARED_DIR);
        File file = new File(base, new File(path).getName());
        // trava simples contra path traversal
        try {
            String basePath = base.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(basePath)) return null;
        } catch (Exception e) {
            return null;
        }
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        File file = resolveFile(uri);
        if (file == null || !file.exists()) return null;

        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols);
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = resolveFile(uri);
        if (file != null && file.exists() && file.delete()) return 1;
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File file = resolveFile(uri);
        if (file == null || !file.exists()) {
            throw new java.io.FileNotFoundException("arquivo não encontrado: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        ParcelFileDescriptor pfd = openFile(uri, mode);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    /** Monta o content:// URI para um arquivo dentro de shared_pdfs/. */
    static Uri uriForFile(String filename) {
        return Uri.parse("content://com.carlos.leitorpdf.fileprovider/" + SHARED_DIR + "/" + filename);
    }
}
