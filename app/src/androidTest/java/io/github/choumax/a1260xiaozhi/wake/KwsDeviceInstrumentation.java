package io.github.choumax.a1260xiaozhi.wake;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import java.util.Arrays;

/** Same-signed, separately installed test APK; exercises the installed release JNI on ARMv7. */
public final class KwsDeviceInstrumentation extends Instrumentation {
    private boolean performanceOnly;
    @Override public void onCreate(Bundle args) { super.onCreate(args); performanceOnly=args!=null&&args.getString("performance", "false").equals("true"); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        StringBuilder evidence = new StringBuilder();
        int passed = 0;
        android.content.Context isolated = new android.content.ContextWrapper(getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                return getTargetContext().getSharedPreferences("kws_instrumentation_isolated",mode);
            }
        };
        isolated.getSharedPreferences("ignored",0).edit().clear().commit();
        new WakeSettings(isolated).setSensitivity(80);
        try {
            String[] words = {"文森特卡索", "蒋友伯", "周望军", "你好周三"};
            int[][] cases = {{3,4,5}, {4,3,5}, {5,3,4}, {0,1,2,3,4,5,6}};
            for (int w = 0; w < (performanceOnly ? 2 : words.length); w++) {
                int selected=performanceOnly?0:w;
                long begin = SystemClock.elapsedRealtime();
                try (WakeKeywordVerifier verifier = new WakeKeywordVerifier(isolated, words[selected],performanceOnly?w+1:1)) {
                    evidence.append("loadMs=").append(SystemClock.elapsedRealtime()-begin)
                        .append(" lexiconMs=").append(verifier.lexiconMillis).append(" nativeMs=").append(verifier.nativeLoadMillis).append(' ');
                    for (int c = 0; c < (performanceOnly?1:cases[selected].length); c++) {
                        short[] pcm = readPcm(cases[selected][c]);
                        begin=SystemClock.elapsedRealtime();
                        boolean hit=false;
                        short[] frame=new short[320];
                        for(int offset=0;offset<pcm.length;offset+=320) {
                            Arrays.fill(frame,(short)0);
                            System.arraycopy(pcm,offset,frame,0,Math.min(320,pcm.length-offset));
                            hit |= verifier.acceptFrame(frame);
                        }
                        Arrays.fill(frame,(short)0);
                        for(int i=0;i<50;i++) hit |= verifier.acceptFrame(frame);
                        long elapsed=SystemClock.elapsedRealtime()-begin;
                        boolean expected=selected<3&&c==0;
                        evidence.append("case=").append(w).append('/').append(c).append(" hit=").append(hit)
                            .append(" expected=").append(expected).append(" ms=").append(elapsed)
                            .append(" audioMs=").append(pcm.length/16).append("; ");
                        Arrays.fill(pcm,(short)0);
                        if(hit!=expected) throw new AssertionError("KWS mismatch at " + w + "/" + c);
                        passed++;
                        Bundle progress=new Bundle(); progress.putString("progress","case="+w+"/"+c+" PASS ms="+elapsed); sendStatus(0,progress);
                    }
                    if (!performanceOnly && w == 1) {
                        // Public fixture 4 + seeded Gaussian noise at 5 dB SNR; old 0.25 misses.
                        short[] degraded = readPcm(7);
                        try {
                            if (verifier.verify(degraded) != WakeKeywordVerifier.Result.MATCH)
                                throw new AssertionError("Degraded speech regression");
                            passed++; evidence.append("degradedSpeech5dB=PASS; ");
                        } finally { Arrays.fill(degraded, (short) 0); }
                    }
                    if (!performanceOnly && w == 2) {
                        short[] positive = readPcm(5);
                        try {
                            for (int take=0;take<3;take++) {
                                if(verifier.verify(positive)!=WakeKeywordVerifier.Result.MATCH)
                                    throw new AssertionError("Three-take verification failed");
                                passed++;
                            }
                        } finally { Arrays.fill(positive,(short)0); }
                        evidence.append("threeTakeVerification=PASS; ");
                    }
                    evidence.append("pssKb=").append(Debug.getPss()).append('\n');
                }
            }
            if (!performanceOnly) {
                WakeSettings settings=new WakeSettings(isolated);
                settings.setEnabled(true);
                try {
                    WakeKeywordVerifier first=WakeKeywordVerifier.acquireListening(isolated,"你好周三");
                    WakeKeywordVerifier.recycleListening(isolated,first);
                    long begin=SystemClock.elapsedRealtime();
                    WakeKeywordVerifier reused=WakeKeywordVerifier.acquireListening(isolated,"你好周三");
                    long reuseMs=SystemClock.elapsedRealtime()-begin;
                    try {
                        if(first!=reused) throw new AssertionError("Model was reloaded");
                        if(reused.verify(new short[16000])!=WakeKeywordVerifier.Result.NO_MATCH)
                            throw new AssertionError("Reused model false trigger");
                        passed++; evidence.append("modelReuseMs=").append(reuseMs).append(" PASS\n");
                    } finally { WakeKeywordVerifier.recycleListening(isolated,reused); }
                    settings.setSensitivity(60);
                    WakeKeywordVerifier adjusted=WakeKeywordVerifier.acquireListening(isolated,"你好周三");
                    try {
                        if (adjusted==reused || Math.abs(adjusted.threshold-0.25f)>0.00001f)
                            throw new AssertionError("Sensitivity did not replace cached model");
                        if (new WakeSettings(isolated).sensitivity()!=60)
                            throw new AssertionError("Sensitivity was not persisted");
                        if(adjusted.verify(new short[16000])!=WakeKeywordVerifier.Result.NO_MATCH)
                            throw new AssertionError("Adjusted model false trigger");
                        passed++; evidence.append("sensitivityPersistenceAndCacheReplacement=PASS\n");
                    } finally { WakeKeywordVerifier.recycleListening(isolated,adjusted); }
                } finally {
                    settings.setEnabled(false);
                    isolated.getSharedPreferences("ignored",0).edit().clear().commit();
                }
            }
            output.putString("result","PASS " + passed + " cases\n" + evidence);
            finish(-1,output);
        } catch(Throwable failure) {
            output.putString("result","FAIL after " + passed + " cases " + failure.getClass().getSimpleName() + "\n" + evidence);
            finish(1,output);
        }
    }
    private short[] readPcm(int sample) throws java.io.IOException {
        byte[] bytes;
        try(java.io.InputStream input=getContext().getAssets().open("kws-tests/"+sample+".pcm")) {
            java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
            byte[] block=new byte[8192]; int count;
            while((count=input.read(block))!=-1) buffer.write(block,0,count);
            bytes=buffer.toByteArray();
        }
        short[] pcm=new short[bytes.length/2];
        for(int i=0;i<pcm.length;i++) pcm[i]=(short)((bytes[2*i]&255)|((bytes[2*i+1]&255)<<8));
        Arrays.fill(bytes,(byte)0);
        return pcm;
    }
}
