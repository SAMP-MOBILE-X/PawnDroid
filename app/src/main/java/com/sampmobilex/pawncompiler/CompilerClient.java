package com.sampmobilex.pawncompiler;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


final class CompilerClient {
    private CompilerClient() { }

    private static final class Result {
        final CountDownLatch ready = new CountDownLatch(1);
        private boolean done;
        private boolean received;
        int status;
        Throwable failure;
        synchronized void record(int code, String path, String detail) {
            if (done) return;
            if (detail != null && !detail.isEmpty()) appendLog(path, detail);
            status = code;
            received = true;
        }
        synchronized void disconnected(String path) {
            complete(received ? status : -100, null, path, received ? null :
                    "Native compiler process stopped unexpectedly. The editor is still open. "
                    + "No AMX was published; retry or check logcat for the native backtrace.");
        }
        synchronized boolean isDone() { return done; }
        synchronized void complete(int code, Throwable error, String path, String detail) {
            if (done) return;
            done = true;
            if (detail != null && !detail.isEmpty()) appendLog(path, detail);
            status = code;
            failure = error;
            ready.countDown();
        }
    }

    static int compile(Context context, String source, String output, String[] includes,
                       String[] flags, String diagnostics, String temp) throws IOException {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Result result = new Result();
        AtomicBoolean bound = new AtomicBoolean();
        AtomicReference<Messenger> service = new AtomicReference<>();
        Runnable startupTimeout = () -> result.complete(-99,
                new IOException("Compiler service did not start within 30 seconds"), diagnostics, null);
        Bundle args = new Bundle();
        args.putString("source", source);
        args.putString("output", output);
        args.putStringArray("includes", includes);
        args.putStringArray("flags", flags);
        args.putString("diagnostics", diagnostics);
        args.putString("temp", temp);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == CompilerService.COMPLETE) {
                String failure = msg.getData().getString("failure", "");
                if (!msg.getData().getBoolean("release", true)) {
                    result.complete(msg.arg1, null, diagnostics, failure);
                    return true;
                }
                result.record(msg.arg1, diagnostics, failure);

                try { service.get().send(Message.obtain(null, CompilerService.RELEASE)); }
                catch (RemoteException error) { result.disconnected(diagnostics); }
            }
            return true;
        }));
        Runnable crashed = () -> result.disconnected(diagnostics);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                main.removeCallbacks(startupTimeout);
                if (result.isDone()) return;
                try {
                    service.set(new Messenger(binder));
                    binder.linkToDeath(crashed::run, 0);
                    Message request = Message.obtain(null, CompilerService.COMPILE);
                    request.setData(args);
                    request.replyTo = reply;
                    service.get().send(request);
                } catch (RemoteException error) { crashed.run(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { crashed.run(); }
            @Override public void onBindingDied(ComponentName name) { crashed.run(); }
            @Override public void onNullBinding(ComponentName name) {
                result.complete(-99, new IOException("Compiler service returned no binder"), diagnostics, null);
            }
        };

        main.postDelayed(startupTimeout, 30000);
        main.post(() -> {
            try {
                boolean ok = app.bindService(new Intent(app, CompilerService.class),
                        connection, Context.BIND_AUTO_CREATE);
                bound.set(ok);
                if (!ok) result.complete(-99, new IOException("Cannot start compiler process"), diagnostics, null);
            } catch (RuntimeException error) { result.complete(-99, error, diagnostics, null); }
        });
        try {
            result.ready.await();
            if (result.failure != null) throw new IOException("Compiler service failed", result.failure);
            return result.status;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Compilation interrupted", error);
        } finally {
            main.removeCallbacks(startupTimeout);
            main.post(() -> { if (bound.get()) app.unbindService(connection); });
        }
    }

    private static void appendLog(String path, String detail) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path, true)) {
            out.write(("\n" + detail + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
