package com.sampmobilex.pawncompiler;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class CompilerService extends Service {
    static final int COMPILE = 1;
    static final int COMPLETE = 2;
    static final int RELEASE = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean accepted;
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == RELEASE) {
            Process.killProcess(Process.myPid());
            return true;
        }
        if (msg.what != COMPILE || msg.replyTo == null) return true;
        if (accepted) {
            Message busy = Message.obtain(null, COMPLETE);
            busy.arg1 = -17;
            Bundle info = new Bundle();
            info.putBoolean("release", false);
            info.putString("failure", "A compilation is already running. Wait for it to finish.");
            busy.setData(info);
            try { msg.replyTo.send(busy); } catch (RemoteException ignored) { }
            return true;
        }
        accepted = true;
        Bundle input = msg.getData();
        Messenger reply = msg.replyTo;
        worker.execute(() -> {
            int status;
            String failure = "";
            try {
                status = PawnNative.compile(input.getString("source"), input.getString("output"),
                        input.getStringArray("includes"), input.getStringArray("flags"),
                        input.getString("diagnostics"), input.getString("temp"));
            } catch (Throwable error) {
                status = -99;
                failure = error.toString();
            }
            Message answer = Message.obtain(null, COMPLETE);
            answer.arg1 = status;
            Bundle detail = new Bundle();
            detail.putString("failure", failure);
            answer.setData(detail);
            try { reply.send(answer); } catch (RemoteException ignored) { }
        });
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }
}
