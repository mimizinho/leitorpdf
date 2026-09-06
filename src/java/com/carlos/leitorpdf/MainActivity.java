package com.carlos.leitorpdf;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_FILE_CHOOSER = 2001;
    // Fluxo novo: não depende de guardar um ValueCallback em memória, então
    // sobrevive à Activity sendo recriada em segundo plano (comum em MIUI)
    // enquanto o seletor de arquivos está aberto.
    static final int REQ_PICK_PDF_NATIVE = 3001;
    static final int REQ_PICK_FOLDER = 4001;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());
        // sem isso, o <input type="file"> do HTML (botão "Adicionar PDF")
        // não abre o seletor de arquivos do Android — fica sem fazer nada.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                try {
                    startActivityForResult(Intent.createChooser(intent, "Escolher PDF"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        // é essa linha que conecta o HTML ao Java: dentro do HTML,
        // window.AndroidPDF.listPdfs() e window.AndroidPDF.readPdfBytes(uri)
        // passam a existir de verdade.
        webView.addJavascriptInterface(new MediaPdfBridge(this), "AndroidPDF");
        webView.loadUrl("file:///android_asset/leitor-pdf-nativo.html");

        requestStoragePermissionIfNeeded();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_PDF_NATIVE) {
            handleNativePickResult(resultCode, data);
            return;
        }

        if (requestCode == REQ_PICK_FOLDER) {
            handleFolderPickResult(resultCode, data);
            return;
        }

        if (requestCode != REQ_FILE_CHOOSER) return;
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    /**
     * Trata o resultado do seletor nativo de PDF (ver MediaPdfBridge.pickPdf()).
     * Diferente do fluxo antigo baseado em ValueCallback, aqui os dados são
     * empurrados direto pro JavaScript via evaluateJavascript, então nada se
     * perde mesmo que a Activity tenha sido recriada enquanto o seletor
     * estava aberto.
     */
    private void handleNativePickResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;

        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty() || webView == null) return;

        org.json.JSONArray jsonArray = new org.json.JSONArray();
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // nem todo provedor de documentos suporta permissão persistente;
                // não é fatal, a leitura imediata abaixo ainda funciona.
            }

            String name = uri.getLastPathSegment();
            long size = 0;
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (nameIdx >= 0) name = cursor.getString(nameIdx);
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                }
            } catch (Exception ignored) {}

            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("uri", uri.toString());
                obj.put("name", name != null ? name : "arquivo.pdf");
                obj.put("size", size);
                jsonArray.put(obj);
            } catch (Exception ignored) {}
        }

        final String js = "window.onNativePicked && window.onNativePicked(" + jsonArray.toString() + ");";
        webView.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * Trata o resultado do seletor de pasta (ACTION_OPEN_DOCUMENT_TREE,
     * disparado por MediaPdfBridge.pickFolder()). Guarda a permissão de
     * forma persistente (sobrevive a reboot) e o URI da pasta escolhida
     * nas preferências do app, pra listPdfs() usar depois.
     */
    private void handleFolderPickResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            // segue mesmo assim: a permissão ainda vale pra sessão atual
        }
        getSharedPreferences("leitorpdf_prefs", MODE_PRIVATE)
                .edit()
                .putString("device_tree_uri", treeUri.toString())
                .apply();

        if (webView != null) {
            webView.evaluateJavascript(
                    "if (typeof syncDevicePdfs === 'function') syncDevicePdfs();", null);
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Tenta abrir a tela especial de "Acesso a todos os arquivos".
            // Em alguns aparelhos/ROMs (ex. MIUI) essa tela não abre de
            // verdade quando chamada apontando pra um app específico, ou
            // nem existe. Por isso isso aqui é só uma tentativa a mais —
            // o caminho garantido é o botão "Escolher pasta" dentro do
            // próprio app (ACTION_OPEN_DOCUMENT_TREE via pickFolder()),
            // que funciona igual em qualquer fabricante.
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    // tela não disponível neste aparelho/ROM: sem problema,
                    // o usuário ainda pode usar "Escolher pasta" no app.
                }
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // pergunta pro JS se tem algo "aberto" (o leitor) pra fechar antes de
        // sair do app de verdade. Ver window.onNativeBackPressed no HTML.
        webView.evaluateJavascript(
            "(typeof onNativeBackPressed === 'function') ? onNativeBackPressed() : false",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (!"true".equals(value)) {
                        exitAppDefault();
                    }
                }
            });
    }

    private void exitAppDefault() {
        super.onBackPressed();
    }

    @Override
    public void onResume() {
        super.onResume();
        // ao voltar da tela de permissão do sistema, manda o HTML
        // tentar de novo listar os PDFs do aparelho
        if (webView != null) {
            webView.evaluateJavascript(
                "if (typeof syncDevicePdfs === 'function') syncDevicePdfs();", null);
        }
    }
}
