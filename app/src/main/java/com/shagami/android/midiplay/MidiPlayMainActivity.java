package com.shagami.android.midiplay;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback2;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;

/**
 * 1トラックシーケンサ(16ステップ)
 * <p/>
 * Satoshi.S (motosumi64@gmail.com)
 */
public class MidiPlayMainActivity extends Activity implements Callback2 {
    // 演奏キーの大きさを定義する
    private static final int _KEY_WIDTH = 90;
    private static final int _KEY_HEIGHT = 160;
    private static final int _MARKER_HEIGHT = 32;
    private static final int _KEYBOARD_Y = 80;
    private static final int _SPEED_Y = 550;

    // ボタンの表示位置
    private ArrayList<Rect> buttonList = null;
    private ArrayList<Rect> markerList = null;
    private ArrayList<Rect> speedList = null;

    private Rect playButton;
    private Rect stopButton;

    private View mControlsView;
    private boolean mVisible;
    private SurfaceView mContentView;
    private SurfaceHolder mHolder;

    private boolean useMIDI;

    private Paint colorKeyOn;          // キーオン
    private Paint colorKeyOff;         // キーオフ
    private Paint colorMarkerOn;      // 演奏マーカー
    private Paint colorMarkerOff;      // 演奏マーカー
    private Paint BG;                   // 背景色

    private Paint colorSpeedSelect;     // 現在選択中のスピード
    private Paint colorSpeedUnSelect;   // 現在非選択のスピード

    private int markPosition;
    private int[] keyOn;

    private int speedPosition;
    private static final int[] tempoList = {60, 100, 120, 144, 160};

    private boolean playing;
    private final static int PLAYSEQUENCE = 1;
    private final static int PLAYSTART = 2;

    private final static byte MIDI_CH = 10;
    private final static byte MIDI_NOTE = 42;
    private final static byte MIDI_VELOCITY = 127;

    private final static int BEATS = 4;                 // 1拍を4つに分割

    private Message msg;
    private MidiInputPort inputPort = null;
    private MidiDevice inputDevice = null;

    private byte[] noteBuf;
    private int noteBufSize;

    // タッチされた位置を1個ずつしらべる
    private int checkTouchPostion(ArrayList<Rect> rectList, int x, int y) {
        Rect r = rectList.get(0);

        if (r.top <= y && r.bottom >= y) {
            Log.d("midi", "x:" + x + "/y:" + y + "/r.t:" + r.top + "/r.b:" + r.bottom);
            for (int i = 0; i < rectList.size(); ++i) {
                r = rectList.get(i);
                if (r.left <= x && r.right >= x) {
                    Log.d("midi", "x:" + x + "/r.l:" + r.left + "/r.r:" + r.right);
                    return i;
                }
            }
        }

        return -1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_midi_play_main);

        mVisible = true;
        mContentView = (SurfaceView) findViewById(R.id.fullscreen_content);

        mContentView.setOnTouchListener(screenTouchEvent);

        mHolder = mContentView.getHolder();
        mHolder.addCallback(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        colorKeyOn = new Paint();
        colorKeyOn.setARGB(0xff, 0x40, 0x40, 0xff);

        colorKeyOff = new Paint();
        colorKeyOff.setARGB(0xff, 0x40, 0x40, 0x80);

        colorMarkerOn = new Paint();
        colorMarkerOn.setARGB(0xff, 0x40, 0xff, 0x40);

        colorMarkerOff = new Paint();
        colorMarkerOff.setARGB(0xff, 0x40, 0x80, 0x40);

        colorSpeedSelect = new Paint();
        colorSpeedSelect.setARGB(0xff, 0xff, 0xff, 0x40);
        colorSpeedSelect.setTextSize(50);

        colorSpeedUnSelect = new Paint();
        colorSpeedUnSelect.setARGB(0xff, 0x80, 0x80, 0x40);
        colorSpeedSelect.setTextSize(50);

        msg = new Message();
        msg.what = PLAYSEQUENCE;

        BG = new Paint();
        BG.setColor(Color.BLACK);

        // キー情報の初期化
        keyOn = new int[16];
        for (int i = 0; i < keyOn.length; ++i) {
            keyOn[i] = 0;
        }

        // 演奏位置の初期化
        markPosition = 0;
        speedPosition = 2;      // 初期テンポは120にする

        Context context = getApplicationContext();

        // パッケージが使えない場合は、MIDIデバイスの列挙をやめる
        useMIDI = false;
        inputDevice = null;

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            // MIDIデバイスの列挙
            MidiManager manager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
            MidiDeviceInfo[] instruments = manager.getDevices();

            if (instruments.length > 0) {
                for (int i = 0; i < instruments.length; ++i) {
                    MidiDeviceInfo instInfo = instruments[i];
                    Bundle properties = instInfo.getProperties();
                    String manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_NAME);

                    Log.d("play", manufacturer);
                    // MIDI楽器にいくつInputポートがあるかを調べる
                    if (instInfo.getInputPortCount() > 0) {
                        // Inputポートが1つでもあれば受信できるので、そのデバイスを使う
                        Log.d("play", "INPUT:" + instInfo.getInputPortCount());
                        // デバイスがOpenできたら、音を鳴らすようにする
                        manager.openDevice(instInfo, new MidiManager.OnDeviceOpenedListener() {
                            @Override
                            public void onDeviceOpened(MidiDevice device) {
                                useMIDI = true;
                                inputDevice = device;

                                inputPort = device.openInputPort(0);

                                noteBuf = new byte[32];
                                noteBuf[noteBufSize++] = (byte) (0x90 + MIDI_CH - 1);
                                noteBuf[noteBufSize++] = MIDI_NOTE;
                                noteBuf[noteBufSize++] = MIDI_VELOCITY;
                            }
                        }, new Handler(Looper.getMainLooper()));
                    }
                }
            }
        }
    }

    // TODO::タッチし続けて、指を動かしたときに継続的に範囲チェックする
    private View.OnTouchListener screenTouchEvent = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            boolean result = false;

            // タッチされたとき
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // キーの範囲ないならば、キーをタッチした処理
                // それ以外の場所ならば、toggle()を呼び出す。
                int x = (int) event.getX();
                int y = (int) event.getY();
                int index;

                // キーの範囲内にいるかどうかを調べる
                if ((index = checkTouchPostion(buttonList, x, y)) >= 0) {
                    // 反転させる
                    keyOn[index] = 1 - keyOn[index];
                    result = true;
                }

                if ((index = checkTouchPostion(speedList, x, y)) >= 0) {
                    speedPosition = index;
                    result = true;
                }

                // 再生中はストップボタンが反応
                // それ以外はプレイボタンが反応
                if (playing) {
                    if (stopButton.top <= y && stopButton.bottom >= y) {
                        if (stopButton.left <= x && stopButton.right >= x) {
                            playing = false;
                            result = true;
                        }
                    }
                } else {
                    if (playButton.top <= y && playButton.bottom >= y) {
                        if (playButton.left <= x && playButton.right >= x) {
                            result = playing = true;
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(PLAYSEQUENCE), 1);
                        }
                    }

                    // 再生してないときに停止ボタンを押すことでマーカーを先頭に戻す
                    if (stopButton.top <= y && stopButton.bottom >= y) {
                        if (stopButton.left <= x && stopButton.right >= x) {
                            markPosition = 0;
                            result = true;
                        }
                    }

                }
            }

            // タッチ処理を行った場合は、画面を更新する
            if (result) {
                draw(mHolder);
            }

            return result;
        }
    };

    // シーケンス実行用Handler
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // 再生中で、メッセージがPLAYSEQUENCEのときに再生処理をする
            if (playing && msg.what == PLAYSEQUENCE) {
                long current = SystemClock.uptimeMillis();

                // 単純に計算してるのでテンポによって誤差がでる
                long nexttime = (long) (60 * 1000 / tempoList[speedPosition] / BEATS);

                Log.d("play", "" + nexttime);

                if (keyOn[markPosition] == 1) {
                    // キーオンを送信する
                    if (useMIDI) {
                        try {
                            inputPort.send(noteBuf, 0, noteBufSize);
                        } catch (Exception ioException) {
                        }
                    }
                } else {
                    // 音の種類のよってはキーオフを送信する
                }

                draw(mHolder);

                // 演奏マーカーの位置を進めたので画面を更新する
                markPosition = (markPosition + 1) & 0x0f;

//                nexttime -= SystemClock.uptimeMillis() - current;

                // 設定されているテンポによってタイミングを設定して
                // メッセージをpostする
                sendMessageDelayed(obtainMessage(PLAYSEQUENCE), nexttime);
            }
        }
    };

    private void initializeButtonPositions() {
        buttonList = new ArrayList<Rect>();
        markerList = new ArrayList<Rect>();
        speedList = new ArrayList<Rect>();

        playButton = new Rect(730, 530, 910, 710);
        stopButton = new Rect(920, 530, 1100, 710);

        // 1小節16個分の位置情報を先に生成しておく
        for (int i = 0; i < 16; ++i) {
            int x = (i + 1) * _KEY_WIDTH;
            int y = _KEYBOARD_Y + _KEY_HEIGHT;

            buttonList.add(new Rect(x, y, x + (_KEY_WIDTH - (_KEY_WIDTH / 10)), y + _KEY_HEIGHT));
            markerList.add(new Rect(x, y + _KEY_HEIGHT + (_KEY_HEIGHT / 10), x + (_KEY_WIDTH - (_KEY_WIDTH / 10)), y + _KEY_HEIGHT + (_KEY_HEIGHT / 10) + _MARKER_HEIGHT));
        }

        for (int i = 0; i < 5; ++i) {
            int x = (i + 1) * _KEY_WIDTH;

            speedList.add(new Rect(x, _SPEED_Y, x + (_KEY_WIDTH - (_KEY_WIDTH / 10)), _SPEED_Y + _KEY_HEIGHT));
        }
    }

    // SurfaceView用の各種処理
    private void draw(SurfaceHolder holder) {
        // canvasをロックする
        Surface surface = holder.getSurface();
        Canvas canvas = surface.lockHardwareCanvas();

        canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), BG);

        // キーボード部分
        // キー、マーカー共に該当してるところはハイライトする
        for (int i = 0; i < 16; ++i) {
            if ((i % 4) == 0) {
                Rect r = buttonList.get(i);
                colorKeyOn.setTextSize(30);
                canvas.drawText("" + (i / 4 + 1), r.left, r.top, colorKeyOn);
            }

            canvas.drawRect(buttonList.get(i), keyOn[i] == 1 ? colorKeyOn : colorKeyOff);
            canvas.drawRect(markerList.get(i), markPosition == i ? colorMarkerOn : colorMarkerOff);
        }

        // スピード調整部分
        for (int i = 0; i < 5; ++i) {
            Rect r = speedList.get(i);
            Paint p = colorSpeedUnSelect;

            if (i == speedPosition) {
                p = colorSpeedSelect;
            }

            p.setAlpha(0x80);
            canvas.drawRect(r, p);

            p.setAlpha(0xff);
            p.setTextSize(30);
            canvas.drawText(Integer.toString(tempoList[i]), r.left, r.top, p);
        }

        canvas.drawText("START", playButton.left, playButton.top, playing ? colorSpeedUnSelect : colorSpeedSelect);
        canvas.drawRect(playButton, playing ? colorSpeedUnSelect : colorSpeedSelect);

        if (markPosition < 1) {
            canvas.drawText("STOP", stopButton.left, stopButton.top, !playing ? colorSpeedUnSelect : colorSpeedSelect);
            canvas.drawRect(stopButton, !playing ? colorSpeedUnSelect : colorSpeedSelect);
        } else {
            canvas.drawText("STOP", stopButton.left, stopButton.top, colorSpeedSelect);
            canvas.drawRect(stopButton, colorSpeedSelect);
        }

        // canvasの変更内容を反映させる
        surface.unlockCanvasAndPost(canvas);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("play", "created");

        initializeButtonPositions();
        draw(holder);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("play", String.format("format:%x, w:%d, h:%d", format, width, height));
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("play", "destroyed");

        // 画面が削除されているので、更新させない
        playing = false;
        mHandler.removeMessages(PLAYSEQUENCE);

        // バッファをすべてflushして、デバイスをcloseする
        try {
            inputPort.flush();
            inputDevice.close();
        } catch (Exception ioException) {
        }
    }

    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        Log.d("play", "redraw");

        draw(holder);
    }
}
