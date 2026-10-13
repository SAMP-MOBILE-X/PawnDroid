package com.sampmobilex.pawncompiler;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class MainActivity extends Activity {
    private static final int LEGACY_STORAGE_REQUEST = 900;
    private static final long MAX_EDITOR_BYTES = 2L * 1024 * 1024;
    private static final int MAX_BROWSER_ITEMS = 1500;
    private static final String DEFAULT_COMPILER_FLAGS = "-d3 -O1 -( -;";
    private static final String QAWNO_COMPILER_FLAGS = "-;+ -(+ -\\ -Z- -d3 -t4";

    private enum BrowseMode { PROJECT, INCLUDES, MAIN, EDIT, OPEN }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> controls = new ArrayList<>();
    private EditText editor, compilerFlags;
    private TextView results, projectStatus, sourceTitle;
    private Button outputButton, compilerOutputButton;
    private AlertDialog compilerOutputDialog;
    private File projectFolder, includeFolder, mainFile, editorFile, lastOutput;
    private File workDir;
    private String loadedText = "";
    private String preferredSourceCharset = "windows-1252";
    private SourceTextCodec.Document editorFormat;
    private String lastCompilerOutput = "";
    private boolean lastCompileSucceeded;
    private volatile boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 35) {
            View root = findViewById(R.id.root);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                int d = (int) (12 * getResources().getDisplayMetrics().density);
                v.setPadding(Math.max(d, bars.left + d), bars.top + d,
                        Math.max(d, bars.right + d), bars.bottom + d);
                return insets;
            });
        }
        editor = findViewById(R.id.sourceEditor);
        compilerFlags = findViewById(R.id.compilerFlags);
        String savedFlags = getPreferences(MODE_PRIVATE).getString("compilerFlags", DEFAULT_COMPILER_FLAGS);
        // Apply the user's VS Code configuration once when upgrading from the Qawno build.
        if (!getPreferences(MODE_PRIVATE).getBoolean("vscodeSettingsApplied", false)) {
            savedFlags = DEFAULT_COMPILER_FLAGS;
            getPreferences(MODE_PRIVATE).edit().putString("compilerFlags", savedFlags)
                    .putString("sourceCharset", "windows-1252")
                    .putBoolean("vscodeSettingsApplied", true).apply();
        }
        compilerFlags.setText(savedFlags);
        preferredSourceCharset = getPreferences(MODE_PRIVATE).getString("sourceCharset", "windows-1252");
        results = findViewById(R.id.results);
        results.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.app_name).setMessage(results.getText())
                .setPositiveButton(R.string.close_window, null).show());
        sourceTitle = findViewById(R.id.sourceTitle);
        projectStatus = findViewById(R.id.projectStatus);
        outputButton = findViewById(R.id.btnExport);
        compilerOutputButton = findViewById(R.id.btnCompilerOutput);
        // Keep the editor monospace at runtime without forcing layoutlib to open font assets.
        editor.setTypeface(Typeface.MONOSPACE);
        results.setTypeface(Typeface.MONOSPACE);
        bind(R.id.btnProject, () -> browse(BrowseMode.PROJECT));
        bind(R.id.btnIncludeFolder, () -> browse(BrowseMode.INCLUDES));
        bind(R.id.btnMain, () -> browse(BrowseMode.MAIN));
        bind(R.id.btnModule, () -> browse(BrowseMode.EDIT));
        bind(R.id.btnOpen, () -> browse(BrowseMode.OPEN));
        bind(R.id.btnSave, () -> saveSourceWithMessage());
        bind(R.id.btnCompile, this::compile);
        bind(R.id.btnCompilerOutput, this::showCompilerOutput);
        findViewById(R.id.btnDiscord).setOnClickListener(v -> openDiscord());
        findViewById(R.id.btnSupport).setOnClickListener(v -> showSupport());
        bind(R.id.btnResetFlags, this::chooseCompilerPreset);
        bind(R.id.btnSourceEncoding, this::chooseSourceEncoding);
        bind(R.id.btnExport, () -> {
            if (lastOutput != null && lastOutput.isFile())
                new AlertDialog.Builder(this)
                        .setTitle(R.string.amx_location_title)
                        .setMessage(lastOutput.getAbsolutePath())
                        .setPositiveButton(R.string.close_window, null)
                        .setNeutralButton(R.string.copy_path, (d, w) -> copyText(
                                getString(R.string.amx_location_title), lastOutput.getAbsolutePath(),
                                getString(R.string.path_copied)))
                        .show();
        });
        workDir = new File(getCacheDir(), "pawn-work");
        workDir.mkdirs();
        restoreSession();
        if (editorFile == null) {
            editor.setText("// Select your gamemode folder, include folder, and main .pwn.\n");
            sourceTitle.setText("Editing: (choose a .pwn or .inc)");
        }
        refreshStatus();
        String nativeVersion;
        try { nativeVersion = PawnNative.compilerVersion(); }
        catch (UnsatisfiedLinkError e) { nativeVersion = "UNKNOWN (native library mismatch: " + e.getMessage() + ")"; }
        say("Native Pawn compiler: " + nativeVersion + "\n" +
                "Choose the gamemode folder, then pick the main .pwn from its files. "
                + "Select the include folder that contains your .inc libraries. "
                + "No bundled includes, imports or copying; .amx stays beside the .pwn.");
    }

    private void bind(int id, Runnable action) {
        Button button = findViewById(id);
        controls.add(button);
        button.setOnClickListener(v -> { if (!busy) action.run(); });
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Button b : controls) b.setEnabled(!value);
        outputButton.setEnabled(!value && lastOutput != null && lastOutput.isFile());
        compilerOutputButton.setEnabled(!value && !lastCompilerOutput.isEmpty());
    }

    private void say(String message) { results.setText(message); }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showCompilerOutput() {
        if (isFinishing() || isDestroyed() || lastCompilerOutput.isEmpty()) return;
        if (compilerOutputDialog != null) compilerOutputDialog.dismiss();
        TextView log = new TextView(this);
        log.setText(lastCompilerOutput);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(getColor(R.color.pc_editor_text));
        log.setTextIsSelectable(true);
        log.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.pc_surface));
        scroll.addView(log);
        compilerOutputDialog = new AlertDialog.Builder(this)
                .setTitle(lastCompileSucceeded ? R.string.compile_success_title : R.string.compile_failed_title)
                .setView(scroll)
                .setPositiveButton(R.string.close_window, null)
                .setNeutralButton(R.string.copy_output, (d, w) -> copyText(
                        getString(R.string.output_label), lastCompilerOutput,
                        getString(R.string.output_copied)))
                .create();
        compilerOutputDialog.show();
        if (compilerOutputDialog.getWindow() != null) {
            compilerOutputDialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        int height = Math.min(dp(380), Math.round(
                getResources().getDisplayMetrics().heightPixels * 0.52f));
        android.view.ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = height;
        scroll.setLayoutParams(params);
    }

    private void reportCompileProblem(String message) {
        lastCompileSucceeded = false;
        lastCompilerOutput = message;
        say(message);
        compilerOutputButton.setEnabled(true);
        showCompilerOutput();
    }

    private void copyText(String label, String value, String confirmation) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show();
        }
    }

    private void openDiscord() {
        String invite = getString(R.string.discord_invite_url);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(invite)));
        } catch (ActivityNotFoundException error) {
            copyText(getString(R.string.discord_label), invite, getString(R.string.discord_invite_copied));
        }
    }

    private void showSupport() {
        TextView details = new TextView(this);
        details.setText(getString(R.string.support_message) + "\n\n"
                + getString(R.string.support_bank) + "\n"
                + getString(R.string.support_account_label) + "\n"
                + getString(R.string.support_account_number));
        details.setTextSize(16);
        details.setTextColor(getColor(R.color.pc_text));
        details.setTextIsSelectable(true);
        details.setPadding(dp(24), dp(8), dp(24), dp(8));
        new AlertDialog.Builder(this)
                .setTitle(R.string.support_label)
                .setView(details)
                .setPositiveButton(R.string.copy_account, (d, w) -> copyText(
                        getString(R.string.support_bank),
                        getString(R.string.support_account_number).replace(" ", ""),
                        getString(R.string.account_copied)))
                .setNegativeButton(R.string.close_window, null)
                .show();
    }

    private void chooseCompilerPreset() {
        String[] labels = {"VS Code", "Qawno"};
        String[] presets = {DEFAULT_COMPILER_FLAGS, QAWNO_COMPILER_FLAGS};
        new AlertDialog.Builder(this)
                .setTitle(R.string.compiler_preset_title)
                .setItems(labels, (dialog, which) -> {
                    compilerFlags.setText(presets[which]);
                    getPreferences(MODE_PRIVATE).edit().putString("compilerFlags", presets[which]).apply();
                    say("Compiler preset: " + labels[which] + "\n" + presets[which]);
                })
                .setNegativeButton(R.string.close_window, null).show();
    }

    private void chooseSourceEncoding() {
        String[] labels = {"Windows-1252 (PC)", "UTF-8"};
        String[] charsets = {"windows-1252", "UTF-8"};
        int selected = preferredSourceCharset.equalsIgnoreCase("UTF-8") ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.source_encoding_title)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferredSourceCharset = charsets[which];
                    getPreferences(MODE_PRIVATE).edit().putString("sourceCharset", preferredSourceCharset).apply();
                    dialog.dismiss();
                    say("Source editor encoding: " + labels[which]
                            + ". Open the file again to use this setting. Existing files are not converted.");
                })
                .setNegativeButton(R.string.close_window, null).show();
    }

    private boolean ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true;
            new AlertDialog.Builder(this)
                    .setTitle("Allow direct project access")
                    .setMessage("To compile .pwn files and write .amx beside them WITHOUT copying "
                            + "your gamemode, Android requires All files access. Grant permission "
                            + "to Pawn Compiler, then return and tap the folder button again. "
                            + "Avoid opening folders under Android/data or Android/obb.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23 &&
                (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, LEGACY_STORAGE_REQUEST);
            return false;
        }
        return true;
    }

    private void browse(BrowseMode mode) {
        if (!ensureStorageAccess()) return;
        File start;
        if ((mode == BrowseMode.MAIN || mode == BrowseMode.EDIT) && projectFolder == null) {
            say("Choose the gamemode folder first.");
            return;
        }
        if (mode == BrowseMode.MAIN || mode == BrowseMode.EDIT) start = projectFolder;
        else if (mode == BrowseMode.PROJECT && projectFolder != null) start = projectFolder;
        else if (mode == BrowseMode.INCLUDES && includeFolder != null) start = includeFolder;
        else if (mode == BrowseMode.OPEN && editorFile != null) start = editorFile.getParentFile();
        else start = Environment.getExternalStorageDirectory();
        showBrowser(start, mode);
    }

    private void showBrowser(File folder, BrowseMode mode) {
        if (folder == null || !folder.isDirectory()) {
            say("Not a readable directory: " + folder); return;
        }
        File[] raw = folder.listFiles();
        if (raw == null) { say("Cannot read directory: " + folder.getAbsolutePath()); return; }
        List<File> entries = new ArrayList<>();
        for (File f : raw) {
            if (f.isDirectory()) entries.add(f);
            else if (mode != BrowseMode.PROJECT && mode != BrowseMode.INCLUDES && isPawnFile(f, mode))
                entries.add(f);
        }
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        boolean truncated = entries.size() > MAX_BROWSER_ITEMS;
        if (truncated) entries = new ArrayList<>(entries.subList(0, MAX_BROWSER_ITEMS));
        final List<File> visible = entries;
        String[] labels = new String[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            File f = visible.get(i);
            labels[i] = (f.isDirectory() ? "📁 " : "📄 ") + f.getName();
        }
        String title = folder.getAbsolutePath() + (truncated ? " (showing first 1500)" : "");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title)
                .setItems(labels, (d, n) -> {
                    File choice = visible.get(n);
                    if (choice.isDirectory()) showBrowser(choice, mode);
                    else openSelectedFile(choice, mode);
                }).setNegativeButton("Cancel", null);
        if (mode == BrowseMode.PROJECT || mode == BrowseMode.INCLUDES) {
            builder.setPositiveButton("Use this folder", (d, n) -> useFolder(folder, mode));
        }
        File parent = folder.getParentFile();
        if (parent != null && parent.getAbsolutePath().startsWith("/storage"))
            builder.setNeutralButton("Up", (d, n) -> showBrowser(parent, mode));
        builder.show();
    }

    private static boolean isPawnFile(File f, BrowseMode mode) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (mode == BrowseMode.MAIN) return n.endsWith(".pwn");
        return n.endsWith(".pwn") || n.endsWith(".inc") || n.endsWith(".pawn");
    }

    private void useFolder(File folder, BrowseMode mode) {
        try {
            saveEditor();
            File canonical = folder.getCanonicalFile();
            if (mode == BrowseMode.PROJECT) {
                projectFolder = canonical;
                // Require an explicit main source choice for every newly selected folder.
                mainFile = null;
                if (editorFile != null && !isInside(editorFile, projectFolder)) {
                    editorFile = null;
                    loadedText = "";
                    editor.setText("");
                    sourceTitle.setText("Editing: (choose main .pwn)");
                }
                lastOutput = null;
                say("Gamemode folder selected:\n" + projectFolder.getAbsolutePath()
                        + "\nNow choose which .pwn to compile. No files were copied.");
            } else {
                includeFolder = canonical;
                lastOutput = null;
                say("Compiler include search root:\n" + includeFolder.getAbsolutePath()
                        + "\nOnly this chosen include library folder is used (plus project source directories)."
                        + "\nNo APK assets or .inc files are imported.");
            }
            persistSession();
            refreshStatus();
            setBusy(false);
            if (mode == BrowseMode.PROJECT) {
                // Present the .pwn browser immediately, instead of making the user
                // discover a separate Choose Main step. Subdirectories remain browsable.
                showBrowser(projectFolder, BrowseMode.MAIN);
            }
        } catch (IOException e) { say("Folder selection failed: " + e.getMessage()); }
    }

    private static boolean isInside(File f, File root) throws IOException {
        if (f == null || root == null) return false;
        String p = f.getCanonicalPath();
        String base = root.getCanonicalPath();
        return p.equals(base) || p.startsWith(base + File.separator);
    }

    private void openSelectedFile(File selected, BrowseMode mode) {
        try {
            saveEditor();
            if (mode == BrowseMode.MAIN) {
                if (!isInside(selected, projectFolder)) throw new IOException("Main must belong to the gamemode folder");
                mainFile = selected.getCanonicalFile();
            }
            if (mode == BrowseMode.OPEN && mainFile == null) {
                mainFile = selected.getCanonicalFile();
                if (projectFolder == null) projectFolder = selected.getParentFile();
            }
            lastOutput = null;
            outputButton.setEnabled(false);
            File file = selected.getCanonicalFile();
            // Selecting the compile target must never load a potentially huge gamemode
            // into Android EditText. This avoids the 2 MB editor guard and UI stalls;
            // the native compiler reads the complete .pwn straight from its path.
            if (mode == BrowseMode.MAIN) {
                editorFile = null;
                editorFormat = null;
                loadedText = "";
                editor.setText("");
                sourceTitle.setText("Main Pawn: " + file.getAbsolutePath());
                say("Selected compilation target:\n" + file.getAbsolutePath()
                        + "\nThe file will be compiled directly from storage without an editor size limit."
                        + (includeFolder == null
                            ? "\nNext: Open Include Folder and choose your .inc library root."
                            : "\nReady to compile with the selected include folder.")
                        + "\nUse Edit Module / Include to open a file for editing.");
                persistSession();
                refreshStatus();
                return;
            }
            editorFile = null; // Updated after reading file safely in worker.
            editorFormat = null;
            loadedText = "";
            editor.setText("");
            sourceTitle.setText("Opening " + file.getName() + "...");
            setBusy(true);
            final String sourceCharset = preferredSourceCharset;
            worker.execute(() -> {
                SourceTextCodec.Document content = null;
                String error = null;
                try { content = readText(file, sourceCharset); }
                catch (IOException e) { error = e.getMessage(); }
                final SourceTextCodec.Document loaded = content;
                final String failed = error;
                runOnUiThread(() -> {
                    if (failed == null) {
                        setEditor(file, loaded);
                        say((mode == BrowseMode.MAIN ? "Selected compilation target: " : "Opened directly: ")
                                + file.getAbsolutePath()
                                + "\nSave updates the ORIGINAL file in storage."
                                + (mode == BrowseMode.MAIN && includeFolder == null
                                    ? "\nNext: Open Include Folder to select your compiler includes."
                                    : ""));
                    } else {
                        // Large sources still compile, even if Android EditText cannot display them.
                        sourceTitle.setText("Not loaded into editor: " + file.getName());
                        say("Source selected, but editor could not open it: " + failed
                                + "\nLarge source files can still be compiled.");
                    }
                    persistSession();
                    refreshStatus();
                    setBusy(false);
                });
            });
        } catch (IOException e) { say("Open failed: " + e.getMessage()); }
    }

    private static SourceTextCodec.Document readText(File file, String charset) throws IOException {
        if (!file.isFile()) throw new IOException("File missing: " + file.getName());
        if (file.length() > MAX_EDITOR_BYTES)
            throw new IOException("File exceeds 2 MB editor limit");
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_EDITOR_BYTES) throw new IOException("File too big for editor");
                out.write(buf, 0, n);
            }
            return SourceTextCodec.decode(out.toByteArray(), charset);
        }
    }

    private void setEditor(File file, SourceTextCodec.Document document) {
        editorFile = file;
        editorFormat = document;
        loadedText = document.text;
        editor.setText(document.text);
        sourceTitle.setText("Editing: " + file.getAbsolutePath() + " [" + document.charset.name() + "]");
    }

    private void saveEditor() throws IOException {
        if (editorFile == null) return;
        String text = editor.getText().toString();
        if (text.equals(loadedText)) return;
        if (editorFormat == null) throw new IOException("Source encoding is unavailable. Open the file again.");
        byte[] bytes = SourceTextCodec.encode(text, editorFormat);
        if (bytes.length > MAX_EDITOR_BYTES) throw new IOException("Edited file exceeds 2 MB");
        if (!editorFile.canWrite()) throw new IOException("No write access to " + editorFile);
        try (FileOutputStream stream = new FileOutputStream(editorFile)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        loadedText = text;
        lastOutput = null;
        outputButton.setEnabled(false);
    }

    private void saveSourceWithMessage() {
        try {
            if (editorFile == null) throw new IOException("Choose a source file from storage first");
            saveEditor();
            say("Saved original file:\n" + editorFile.getAbsolutePath());
        } catch (IOException e) { say("Save failed: " + e.getMessage()); }
    }


    private static String[] parseCompilerFlags(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty())
            throw new IOException("Compiler flags are empty. Press Preset to choose Qawno or VS Code settings.");
        String[] parts = raw.trim().split("\\s+");
        if (parts.length > 32) throw new IOException("Too many compiler flags (maximum 32)");
        for (String flag : parts) {
            if (!flag.startsWith("-") || flag.length() < 2 || flag.length() > 80)
                throw new IOException("Invalid compiler flag: " + flag);
            if (flag.indexOf('"') >= 0 || flag.indexOf((char)39) >= 0)
                throw new IOException("Enter only switches here; no quoted file paths: " + flag);
            // The Android app owns include, output, report and diagnostic paths.
            // Assembly/listing options change the output format and are not supported here.
            if (flag.startsWith("-i") || flag.startsWith("-o") || flag.startsWith("-r")
                    || flag.startsWith("-e") || flag.startsWith("-a") || flag.startsWith("-l"))
                throw new IOException("Use the folder picker for -i/-o/-r; unsupported path/output flag: " + flag);
        }
        return parts;
    }

    private void compile() {
        if (!ensureStorageAccess()) return;
        final String[] selectedFlags;
        try {
            selectedFlags = parseCompilerFlags(compilerFlags.getText().toString());
        } catch (IOException e) {
            reportCompileProblem("Invalid compiler options: " + e.getMessage());
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
                .putString("compilerFlags", compilerFlags.getText().toString().trim()).apply();
        try {
            if (projectFolder == null || !projectFolder.isDirectory())
                throw new IOException("Open the gamemode folder first");
            if (mainFile == null || !mainFile.isFile())
                throw new IOException("Choose the main .pwn from the selected gamemode folder first");
            if (!isInside(mainFile, projectFolder))
                throw new IOException("Main .pwn is outside the selected gamemode folder");
            if (includeFolder == null || !includeFolder.isDirectory())
                throw new IOException("Open your .inc include folder first (e.g. pawno/include)");
            if (!includeFolder.canRead())
                throw new IOException("Cannot read selected include folder: " + includeFolder);
            saveEditor();
            if (!mainFile.canRead()) throw new IOException("Source cannot be read: " + mainFile);
            if (!mainFile.getParentFile().canWrite())
                throw new IOException("AMX output folder is not writable: " + mainFile.getParent());
        } catch (IOException ex) { reportCompileProblem("Cannot compile: " + ex.getMessage()); return; }
        File src = mainFile;
        File dest = new File(src.getParentFile(), stripPwn(src.getName()) + ".amx");
        // Build to a temporary .amx BESIDE the main .pwn to retain the old AMX on errors.
        String requestId = UUID.randomUUID().toString();
        File tmp = new File(src.getParentFile(), "." + stripPwn(src.getName()) + "." + requestId + ".build.amx");
        File diagnostic = new File(workDir, "compiler-" + requestId + ".txt");
        lastOutput = null;
        setBusy(true);
        say("Compiling " + src.getName() + "…\nNative Pawn compiler: " + PawnNative.compilerVersion() + "\n" +
                "Compiling directly from:\n" + src.getAbsolutePath()
                + "\nIncludes: " + includeFolder.getAbsolutePath()
                + "\nOutput: " + dest.getAbsolutePath()
                + "\nFlags: " + String.join(" ", selectedFlags));
        worker.execute(() -> {
            int result = -99;
            String detail = "";
            try {
                String[] paths = makeIncludePaths(src);
                if (tmp.exists() && !tmp.delete()) throw new IOException("Cannot remove previous temporary AMX");
                if (diagnostic.exists() && !diagnostic.delete()) throw new IOException("Cannot reset compiler log");
                result = CompilerClient.compile(getApplicationContext(), src.getAbsolutePath(), tmp.getAbsolutePath(),
                        paths, selectedFlags, diagnostic.getAbsolutePath(), getCacheDir().getAbsolutePath());
                detail = diagnostic.isFile() ? readCompilerLog(diagnostic) : "No compiler log";
                if (result == 0 && tmp.isFile() && tmp.length() > 0) {
                    if (!tmp.renameTo(dest)) throw new IOException("Could not move AMX to " + dest
                            + " (check storage access and file permissions)");
                } else {
                    tmp.delete();
                }
            } catch (Exception | UnsatisfiedLinkError e) {
                result = -99;
                detail = e.toString() + "\n" + detail;
                tmp.delete();
            }
            diagnostic.delete();
            final boolean ok = result == 0 && dest.isFile() && dest.length() > 0 && !tmp.exists();
            final String log = detail;
            final int code = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                lastOutput = ok ? dest : null;
                lastCompileSucceeded = ok;
                lastCompilerOutput = (ok ? "COMPILED · " + dest.length() + " bytes\n" + dest.getAbsolutePath()
                        : "COMPILE FAILED · status " + code + compileFailureDescription(code)) + "\n\n" + log;
                setBusy(false);
                say(ok ? "Compiled " + src.getName() + " · " + dest.length() + " bytes"
                        : "Compile failed · open Output for details");
                showCompilerOutput();
            });
        });
    }

    private static String compileFailureDescription(int code) {
        switch (code) {
            case -10: return "\nMissing native compiler arguments.";
            case -11: return "\nA source, output, log, or cache path is invalid or too long.";
            case -12: return "\nNot enough memory to prepare the compiler request.";
            case -13: return "\nAn include folder or compiler argument list is invalid.";
            case -14: return "\nCould not open the compiler log or prepare the cache directory.";
            case -15: return "\nA compiler switch is unsupported or invalid.";
            case -16: return "\nLegacy directory/setup failure. Check that Android runtime r4 is installed.";
            case -17: return "\nA compilation is already running. Wait for it to finish, then retry.";
            case -100: return "\nThe native compiler process stopped unexpectedly.";
            default: return "";
        }
    }

    private static String stripPwn(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".pwn")
                ? name.substring(0, name.length() - 4) : name;
    }

    private String[] makeIncludePaths(File src) {
        return IncludePathResolver.resolve(src, projectFolder, includeFolder);
    }

    private static String readCompilerLog(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1 && out.size() < 200000)
                out.write(buffer, 0, Math.min(n, 200000 - out.size()));
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void refreshStatus() {
        projectStatus.setText("Project: " + label(projectFolder)
                + "\nIncludes: " + label(includeFolder)
                + "\nMain: " + label(mainFile));
    }

    private static String label(File file) { return file == null ? "not selected" : file.getAbsolutePath(); }

    private void persistSession() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("directProject", projectFolder == null ? "" : projectFolder.getAbsolutePath())
                .putString("directInclude", includeFolder == null ? "" : includeFolder.getAbsolutePath())
                .putString("directMain", mainFile == null ? "" : mainFile.getAbsolutePath())
                .apply();
    }

    private void restoreSession() {
        String a = getPreferences(MODE_PRIVATE).getString("directProject", "");
        String b = getPreferences(MODE_PRIVATE).getString("directInclude", "");
        String c = getPreferences(MODE_PRIVATE).getString("directMain", "");
        if (!a.isEmpty() && new File(a).isDirectory()) projectFolder = new File(a);
        if (!b.isEmpty() && new File(b).isDirectory()) includeFolder = new File(b);
        if (!c.isEmpty() && new File(c).isFile() && projectFolder != null) {
            try {
                if (isInside(new File(c), projectFolder)) mainFile = new File(c);
            } catch (IOException ignored) {  }
        }

    }

    @Override protected void onDestroy() {
        if (compilerOutputDialog != null) compilerOutputDialog.dismiss();
        worker.shutdown();
        super.onDestroy();
    }
}
