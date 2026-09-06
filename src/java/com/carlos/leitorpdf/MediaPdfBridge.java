package com.carlos.leitorpdf; // troque pelo pacote do seu projeto no Sketchware Pro

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ponte entre o WebView (leitor-pdf-nativo.html) e o Android nativo.
 *
 * Registro no código da Activity (na tela onde está o WebView):
 *
 *   webView.getSettings().setJavaScriptEnabled(true);
 *   webView.addJavascriptInterface(new MediaPdfBridge(this), "AndroidPDF");
 *   webView.loadUrl("file:///android_asset/leitor-pdf-nativo.html");
 *
 * No Sketchware Pro, isso normalmente é feito através de um bloco de
 * "código customizado" / "extra Java" dentro do evento onCreate da tela,
 * já que a interface gráfica de blocos não tem um botão pronto para
 * addJavascriptInterface.
 */
public class MediaPdfBridge {

    private static final String PREFS = "leitorpdf_prefs";
    private static final String KEY_TREE_URI = "device_tree_uri";

    private final Context context;

    public MediaPdfBridge(Context context) {
        this.context = context;
    }

    /**
     * Diz se o usuário já escolheu uma pasta pelo seletor do Android
     * (ACTION_OPEN_DOCUMENT_TREE). Esse caminho é o preferido: funciona
     * igual em qualquer fabricante/ROM, diferente da tela especial de
     * "Acesso a todos os arquivos", que em ROMs como MIUI às vezes não
     * abre de verdade quando chamada apontando pra um app específico.
     */
    @JavascriptInterface
    public boolean hasFolderAccess() {
        return !getSavedTreeUri().isEmpty();
    }

    private String getSavedTreeUri() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TREE_URI, "");
    }

    /**
     * Abre o seletor de pasta padrão do Android. O usuário escolhe uma
     * pasta uma única vez (ex.: a raiz do armazenamento interno ou a
     * pasta Download) e o app passa a poder listar/ler os PDFs dentro
     * dela pra sempre, sem pedir de novo. O resultado é tratado em
     * MainActivity.onActivityResult -> handleFolderPickResult.
     */
    @JavascriptInterface
    public void pickFolder() {
        if (!(context instanceof Activity)) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            ((Activity) context).startActivityForResult(intent, MainActivity.REQ_PICK_FOLDER);
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao abrir seletor de pasta", e);
        }
    }

    /**
     * Abre o seletor de documentos do Android diretamente (sem passar pelo
     * onShowFileChooser/ValueCallback do WebView, que se perde se a Activity
     * for recriada em segundo plano). O resultado é entregue de volta ao
     * JavaScript via MainActivity.onActivityResult -> evaluateJavascript.
     */
    /**
     * Diz se o app já tem a permissão "Acesso a todos os arquivos"
     * (necessária a partir do Android 11 pra enxergar PDFs que outros apps
     * baixaram, não só os escolhidos manualmente no seletor).
     */
    @JavascriptInterface
    public boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        // Android 9 e abaixo: reflete de verdade se a permissão de
        // armazenamento foi concedida, em vez de sempre responder "sim"
        // (isso escondia a faixa "Escolher pasta" mesmo sem acesso real).
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Abre a tela do Android pra conceder "Acesso a todos os arquivos". */
    @JavascriptInterface
    public void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao abrir tela de permissão", e);
        }
    }

    @JavascriptInterface
    public void pickPdf() {
        if (!(context instanceof Activity)) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            ((Activity) context).startActivityForResult(intent, MainActivity.REQ_PICK_PDF_NATIVE);
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao abrir seletor de PDF", e);
        }
    }

    /**
     * Salva os bytes (base64) recebidos do JS na pasta pública de Downloads
     * do aparelho. Retorna "" em caso de sucesso, ou uma mensagem de erro.
     */
    @JavascriptInterface
    public String saveToDownloads(String base64, String filename) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            if (filename == null || filename.isEmpty()) filename = "documento.pdf";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: escrita direta em pastas públicas com File é
                // bloqueada pelo armazenamento com escopo, mesmo com a
                // permissão "todos os arquivos" concedida. O caminho correto
                // é inserir via MediaStore, que não exige permissão nenhuma
                // pra gravar no Downloads do próprio app.
                android.content.ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);

                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = resolver.insert(collection, values);
                if (item == null) return "não foi possível criar o arquivo";
                try (java.io.OutputStream os = resolver.openOutputStream(item)) {
                    if (os == null) return "não foi possível abrir o arquivo pra escrita";
                    os.write(bytes);
                }
                return "";
            } else {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File out = uniqueFile(dir, filename);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(bytes);
                }
                return "";
            }
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao salvar em Downloads", e);
            return e.getMessage() != null ? e.getMessage() : "erro desconhecido";
        }
    }

    /**
     * Grava os bytes (base64) num arquivo temporário em cache/shared_pdfs/ e
     * abre o seletor de apps do Android (ACTION_SEND) apontando pra ele via
     * PdfFileProvider. Retorna "" em caso de sucesso, ou mensagem de erro.
     */
    @JavascriptInterface
    public String sharePdf(String base64, String filename) {
        if (!(context instanceof Activity)) return "contexto inválido";
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            File dir = new File(context.getCacheDir(), "shared_pdfs");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, filename != null && !filename.isEmpty() ? filename : "documento.pdf");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(bytes);
            }

            android.net.Uri uri = PdfFileProvider.uriForFile(out.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(send, "Enviar PDF"));
            return "";
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao compartilhar pdf", e);
            return e.getMessage() != null ? e.getMessage() : "erro desconhecido";
        }
    }

    private File uniqueFile(File dir, String filename) {
        if (filename == null || filename.isEmpty()) filename = "documento.pdf";
        File candidate = new File(dir, filename);
        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            base = filename.substring(0, dot);
            ext = filename.substring(dot);
        }
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            i++;
        }
        return candidate;
    }

    /**
     * Varre o armazenamento do aparelho (MediaStore) por arquivos PDF.
     * Retorna uma String JSON no formato:
     * [{"uri":"content://...","name":"arquivo.pdf","size":12345}, ...]
     *
     * Requer permissão de leitura de armazenamento concedida em tempo de
     * execução (ver nota sobre permissões no final do arquivo).
     */
    @JavascriptInterface
    public String listPdfs() {
        // chave = caminho absoluto do arquivo, pra não listar o mesmo PDF
        // duas vezes quando ele aparece tanto no MediaStore quanto na
        // varredura manual.
        Map<String, JSONObject> byPath = new LinkedHashMap<>();

        // 1) MediaStore: rápido quando o arquivo já foi indexado pelo
        // scanner de mídia do Android (normalmente acontece pra downloads
        // feitos pelo navegador, por exemplo).
        try {
            String[] projection = {
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
            };
            // Busca por nome de arquivo (LIKE '%.pdf') em vez de MIME_TYPE:
            // muitos aparelhos deixam a coluna MIME_TYPE vazia para
            // documentos (só preenchem de verdade pra foto/vídeo/áudio),
            // então filtrar por MIME_TYPE='application/pdf' costumava
            // devolver zero resultados mesmo com o PDF presente.
            String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {"%.pdf"};
            Uri collection = MediaStore.Files.getContentUri("external");

            try (Cursor cursor = context.getContentResolver().query(
                    collection, projection, selection, selectionArgs, null)) {
                if (cursor != null) {
                    int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                    int dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);

                    while (cursor.moveToNext()) {
                        String path = dataCol >= 0 ? cursor.getString(dataCol) : null;
                        if (path == null) continue;
                        String name = cursor.getString(nameCol);
                        long size = cursor.getLong(sizeCol);
                        // DATE_MODIFIED no MediaStore vem em segundos desde a
                        // época; o JS espera milissegundos (new Date(ms)).
                        long dateModified = dateCol >= 0 ? cursor.getLong(dateCol) * 1000L : 0L;
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("uri", Uri.fromFile(new File(path)).toString());
                            obj.put("name", name);
                            obj.put("size", size);
                            if (dateModified > 0) obj.put("dateModified", dateModified);
                            byPath.put(path, obj);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao consultar MediaStore", e);
        }

        // 2) Varredura direta do armazenamento: só roda se o app tiver
        // "Acesso a todos os arquivos" concedido (ou em versões antigas do
        // Android, que não têm essa restrição). Isso garante que PDFs que o
        // scanner de mídia ainda não indexou (ex.: copiados por cabo/ADB)
        // também apareçam. Em muitos aparelhos (ex. MIUI) essa permissão
        // especial nunca chega a ser concedida, então isso aqui é só um
        // extra — o caminho garantido é o item 3 logo abaixo.
        // Varre TODOS os volumes montados (armazenamento interno + cartão
        // SD + pendrive OTG), não só o armazenamento interno principal.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            try {
                for (File root : getStorageRoots()) {
                    scanDirForPdfs(root, byPath, 0);
                }
            } catch (Exception e) {
                Log.e("MediaPdfBridge", "erro ao varrer armazenamento", e);
            }
        }

        // 3) Pasta escolhida manualmente pelo usuário via seletor padrão do
        // Android (ACTION_OPEN_DOCUMENT_TREE). Esse caminho funciona igual
        // em qualquer aparelho/fabricante, sem depender de nenhuma tela de
        // permissão especial — é o mais confiável dos três.
        String treeUriStr = getSavedTreeUri();
        if (!treeUriStr.isEmpty()) {
            try {
                Uri treeUri = Uri.parse(treeUriStr);
                scanTreeForPdfs(treeUri, DocumentsContract.getTreeDocumentId(treeUri), byPath, 0);
            } catch (Exception e) {
                Log.e("MediaPdfBridge", "erro ao varrer pasta escolhida", e);
            }
        }

        JSONArray result = new JSONArray();
        for (JSONObject obj : byPath.values()) result.put(obj);
        return result.toString();
    }

    /**
     * Descobre a raiz de cada volume de armazenamento montado (armazenamento
     * interno principal + cartão SD + pendrive OTG), pra varrer todos, não
     * só o armazenamento interno.
     *
     * Truque: Context.getExternalFilesDirs(null) devolve uma pasta
     * própria do app dentro de CADA volume montado, algo como
     * "/storage/emulated/0/Android/data/com.carlos.leitorpdf/files" (interno)
     * e "/storage/1234-5678/Android/data/com.carlos.leitorpdf/files" (SD/OTG).
     * Subindo 4 níveis a partir daí chega na raiz pública do volume
     * ("/storage/emulated/0" ou "/storage/1234-5678"), sem precisar de
     * StorageManager (API 24+) nem de bibliotecas externas.
     */
    private List<File> getStorageRoots() {
        List<File> roots = new ArrayList<>();
        try {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir == null) continue;
                    File root = dir;
                    for (int i = 0; i < 4 && root != null; i++) root = root.getParentFile();
                    if (root != null && root.exists() && !containsPath(roots, root)) roots.add(root);
                }
            }
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao listar volumes de armazenamento", e);
        }
        if (roots.isEmpty()) roots.add(Environment.getExternalStorageDirectory());
        return roots;
    }

    private boolean containsPath(List<File> list, File f) {
        String p = f.getAbsolutePath();
        for (File existing : list) if (existing.getAbsolutePath().equals(p)) return true;
        return false;
    }

    /**
     * Varre recursivamente uma pasta procurando arquivos .pdf, ignorando a
     * pasta "Android" (dados isolados de outros apps, sem permissão de
     * leitura e sem PDFs relevantes) e arquivos/pastas ocultos. Limita a
     * profundidade pra não travar em estruturas muito profundas.
     */
    private void scanDirForPdfs(File dir, Map<String, JSONObject> out, int depth) {
        if (dir == null || depth > 10) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;

            if (f.isDirectory()) {
                if ("Android".equals(n)) {
                    // "Android/data" é privado de outros apps (sem permissão
                    // de leitura, sem PDFs relevantes) — mas "Android/media"
                    // é pública, e é onde apps modernos (WhatsApp, Telegram)
                    // guardam os documentos/mídia compartilhados desde que
                    // passaram a seguir o armazenamento com escopo. Antes
                    // essa pasta "Android" inteira era pulada, o que também
                    // escondia o "media" por engano.
                    File mediaDir = new File(f, "media");
                    if (mediaDir.isDirectory()) scanDirForPdfs(mediaDir, out, depth + 1);
                    continue;
                }
                scanDirForPdfs(f, out, depth + 1);
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                String path = f.getAbsolutePath();
                if (out.containsKey(path)) continue;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("uri", Uri.fromFile(f).toString());
                    obj.put("name", n);
                    obj.put("size", f.length());
                    // File.lastModified() já vem em milissegundos.
                    long lastMod = f.lastModified();
                    if (lastMod > 0) obj.put("dateModified", lastMod);
                    out.put(path, obj);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Varre recursivamente a pasta escolhida pelo usuário via
     * ACTION_OPEN_DOCUMENT_TREE, usando a API de DocumentsContract (não
     * precisa da lib androidx.documentfile, que não está disponível neste
     * projeto sem Gradle/Maven).
     */
    private void scanTreeForPdfs(Uri treeUri, String parentDocId, Map<String, JSONObject> out, int depth) {
        if (depth > 12) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = context.getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.getLong(3);
                // COLUMN_LAST_MODIFIED já vem em milissegundos (pode vir 0/nulo
                // se o provedor não informar).
                long lastMod = cursor.isNull(4) ? 0L : cursor.getLong(4);
                if (docId == null) continue;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanTreeForPdfs(treeUri, docId, out, depth + 1);
                } else if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    String key = "saf:" + docId;
                    if (out.containsKey(key)) continue;
                    try {
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        JSONObject obj = new JSONObject();
                        obj.put("uri", docUri.toString());
                        obj.put("name", name);
                        obj.put("size", size);
                        if (lastMod > 0) obj.put("dateModified", lastMod);
                        out.put(key, obj);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao listar pasta (SAF): " + parentDocId, e);
        }
    }

    /**
     * Lê o conteúdo de um PDF a partir da URI retornada por listPdfs()
     * e devolve em Base64, pronto para o JS reconstruir como Blob.
     */
    @JavascriptInterface
    public String readPdfBytes(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return "";

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
            in.close();

            return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e("MediaPdfBridge", "erro ao ler PDF", e);
            return "";
        }
    }
}

/*
 * PERMISSÕES NECESSÁRIAS (AndroidManifest.xml):
 *
 * <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
 *     android:maxSdkVersion="32" />
 * <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
 * <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
 *
 * A partir do Android 11 (API 30), pra varrer TODOS os PDFs do
 * armazenamento (não só os que o próprio app criou), é preciso pedir a
 * permissão especial "Acesso a todos os arquivos", que só pode ser
 * concedida manualmente pelo usuário numa tela de configurações:
 *
 *   Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
 *   intent.setData(Uri.parse("package:" + context.getPackageName()));
 *   context.startActivity(intent);
 *
 * Sem isso, listPdfs() ainda funciona, mas só enxerga PDFs que já passaram
 * pelo picker do próprio app (comportamento parecido com o antigo).
 */
