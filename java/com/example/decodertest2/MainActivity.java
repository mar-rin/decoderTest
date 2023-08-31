package com.example.decodertest2;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

public class MainActivity extends AppCompatActivity {

    private MediaExtractor extractor;
    private SurfaceView surfaceView;
    private Thread decoderThread;
    private volatile boolean running = false;
    private int CAMERA_REQUEST_CODE = 100;
    private Camera camera;
    private MediaCodec encoder;
    private MediaCodec.BufferInfo encoderBufferInfo;
    private Surface encoderSurface;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec decoder;
    private byte[] codecConfigData;
    private boolean isDecoderInitialized = false;
    private boolean cameraOpened = false;
    private boolean surfaceReady = false;
    private int TIMEOUT = 10000;
    private EncodedBufferInfo currentEncodedBufferInfo;
    private final Object syncObject = new Object();
    Queue<EncodedBufferInfo> encodedBufferQueue = new LinkedList<>();
    private static final int MAX_QUEUE_SIZE = 10;
    private boolean isEncoderPaused = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceView);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                running = true;
                surfaceReady = true;
                if (cameraOpened) {
                    initMediaComponents();
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                running = false;
                if (extractor != null) {
                    extractor.release();
                    extractor = null;
                }
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }

    @SuppressLint("MissingPermission")
    private void initializeCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = cameraManager.getCameraIdList()[0];
            Log.i("CameraID ", cameraId);
            HandlerThread handlerThread = new HandlerThread("CameraBackground");
            handlerThread.start();
            Handler backgroundHandler = new Handler(handlerThread.getLooper());

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    cameraOpened = true;
                    if (surfaceReady) {
                        initMediaComponents();
                    }
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void initMediaComponents() {
        try {
            setupEncoder();
            encoder.start();
            startPreview();
        } catch (IOException e) {
            throw new RuntimeException("Error setting up media components", e);
        }
    }

    private void setupEncoder() throws IOException {
        encoder = MediaCodec.createEncoderByType("video/avc");
        if (encoder == null) {
            Log.e("MainActivity", "Encoder is null. Cannot proceed.");
            return;
        }

        ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
        params.width = 1280;
        params.height = 720;
        surfaceView.setLayoutParams(params);

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                if (!isEncoderPaused) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(index);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        decoder.queueInputBuffer(index, 0, currentEncodedBufferInfo.size, currentEncodedBufferInfo.presentationTimeUs, currentEncodedBufferInfo.flags);
                        currentEncodedBufferInfo.data = null;
                    }
                }
            }
            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // This buffer contains codec configuration data (CSD) instead of actual video data.
                    byte[] csd = new byte[info.size];
                    outputBuffer.get(csd);
                    handleCodecConfigData(csd);
                    Log.i("CodecConfig ", "Print: " + csd);
                    encoder.releaseOutputBuffer(index, false);
                    return;
                } else {
                    if (isEncoderPaused) {
                        encoder.releaseOutputBuffer(index, false);
                        return;
                    }
                    ByteBuffer encodedData = ByteBuffer.allocate(info.size);
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    encodedData.put(outputBuffer);
                    encodedData.flip();
                    synchronized (syncObject) {
                        EncodedBufferInfo ebi = new EncodedBufferInfo(info.offset, info.size, info.presentationTimeUs, info.flags);
                        ebi.data = encodedData;
                        encodedBufferQueue.offer(ebi);
                    }
                    Log.i("EncodedData", String.valueOf(encodedData));
                    if (encodedBufferQueue.size() > MAX_QUEUE_SIZE) {
                        isEncoderPaused = true;
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            }
            private void handleCodecConfigData(byte[] csd) {
                codecConfigData = csd;
                if (!isDecoderInitialized) {
                    initDecoder(surfaceView.getHolder().getSurface());
                    isDecoderInitialized = true;
                }
            }
            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            }
            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            }
        });
    }


    private void startPreview() {
        try {
            final CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(encoderSurface);
            int orientation = getOrientation();
            requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            cameraDevice.createCaptureSession(Arrays.asList(encoderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        session.setRepeatingRequest(requestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private int getOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private void initDecoder(Surface surface) {
        try {
            decoder = MediaCodec.createDecoderByType("video/avc"); // H.264/AVC codec
            MediaCodecInfo codecInfo = selectCodec("video/avc");
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType("video/avc");
            int[] colorFormats = capabilities.colorFormats;
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", surfaceView.getWidth(), surfaceView.getHeight());
            format.setByteBuffer("csd-0", ByteBuffer.wrap(codecConfigData));
            decoder.configure(format, surface, null, 0);
            decoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    Log.i("DecoderInput", "bufferAvailableCalled");
                    ByteBuffer inputBuffer = codec.getInputBuffer(index);
                    synchronized (syncObject) { // Ensure synchronized access
                        EncodedBufferInfo ebi = encodedBufferQueue.poll();
                        if (ebi != null && ebi.size > 0) {
                            inputBuffer.clear();
                            inputBuffer.put(ebi.data);
                            Log.i("DecoderInput", "SEttingData");
                            decoder.queueInputBuffer(index, 0, ebi.size, ebi.presentationTimeUs, ebi.flags);
                            if (isEncoderPaused && encodedBufferQueue.size() < MAX_QUEUE_SIZE / 2) {
                                isEncoderPaused = false;
                            }
                        } else if (ebi == null) {
                            // This means we're out of frames to decode; handle accordingly
                        }
                    }
                }
                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    // When we have a frame ready for rendering, we release it for rendering on the Surface
                    Log.i("DecoderOutput", "something is cooking...");
                    decoder.releaseOutputBuffer(index, true);
                }
                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    e.printStackTrace();
                }
                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                }
            });
            decoder.start();
        } catch (IOException e) {
            throw new RuntimeException("Error initializing decoder", e);
        }
    }

    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                String[] types = codecInfo.getSupportedTypes();
                for (String type : types) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return codecInfo;
                    }
                }
            }
        }
        return null;
    }


    //Streaming live camera feed in the SurfaceView, without encoding/decoding pipeline
/*    private void startCameraPreview(SurfaceHolder holder) {
        try {
            camera = Camera.open(); // opens the default camera, you can specify a camera id for multi-camera devices
            if (camera == null) {
                // Handle the error, e.g., show an error message
                return;
            }
            setCameraDisplayOrientation(MainActivity.this, Camera.CameraInfo.CAMERA_FACING_BACK, camera);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/

    /*    private void stopCameraPreview() {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }*/

    //used for old Camera API, not compatible with Camera2
    /*private void setCameraDisplayOrientation(Activity activity, int cameraId, Camera camera) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // Compensate for the mirror view
        } else {  // Back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }
*/



}