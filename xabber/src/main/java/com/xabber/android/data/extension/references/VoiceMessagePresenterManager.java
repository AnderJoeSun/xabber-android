package com.xabber.android.data.extension.references;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;

import com.xabber.android.data.Application;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.ui.widget.PlayerVisualizerView;
import com.xabber.android.utils.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class VoiceMessagePresenterManager {

    private static VoiceMessagePresenterManager instance;

    private static final Map<String, ArrayList<Integer>> voiceWaveData = new HashMap<>();
    private static final HashSet<String> waveInProgress = new HashSet<>();

    public static VoiceMessagePresenterManager getInstance() {
        if (instance == null)
            instance = new VoiceMessagePresenterManager();
        return instance;
    }

    public VoiceMessagePresenterManager() {}

    public void sendWaveDataIfSaved(final String filePath, final PlayerVisualizerView view) {
        if (voiceWaveData.get(filePath) != null) {
            view.updateVisualizer(voiceWaveData.get(filePath));
        } else
            Application.getInstance().runInBackgroundUserRequest(new Runnable() {
                @Override
                public void run() {
                    File file = new File(filePath);
                    int size = (int) file.length();
                    final byte[] bytes = new byte[size];
                    try {
                        BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
                        buf.read(bytes, 0, bytes.length);
                        buf.close();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (waveInProgress.contains(file.getPath())) return;
                    else {
                        waveInProgress.add(file.getPath());
                        mediaDecoderTest(file, view);
                    }
                }
            });
    }


    public void mediaDecoderTest(final File file, final PlayerVisualizerView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final MediaCodec codec;
            MediaFormat format;
            final ArrayList<Float> sampleArray = new ArrayList<>();
            final ArrayList<Integer> squashedArray = new ArrayList<>();
            try {
                final MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(file.getPath());
                format = extractor.getTrackFormat(0);
                extractor.selectTrack(0);

                String decoderForFormat = new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format);
                codec = MediaCodec.createByCodecName(decoderForFormat);

                codec.setCallback(new MediaCodec.Callback() {
                    @Override
                    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int index) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        int size = extractor.readSampleData(input, 0);
                        if (size > 0) {
                            extractor.advance();
                            codec.queueInputBuffer(index, 0, input.limit(), extractor.getSampleTime(), 0);
                        } else {
                            codec.queueInputBuffer(index, 0, 0, extractor.getSampleTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }

                    @Override
                    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int index, @NonNull MediaCodec.BufferInfo bufferInfo) {
                        ByteBuffer output = codec.getOutputBuffer(index);
                        MediaFormat bufferFormat = codec.getOutputFormat(index);
                        int pcmType;
                        try {
                            pcmType = bufferFormat.getInteger("123");
                        } catch (RuntimeException e) {
                            LogManager.e(this, e.getMessage());
                            pcmType = 0;
                        }

                        //int pcmType = bufferFormat.getInteger("pcm-encoding");//2 - 16bit (short); [-32768;32767].
                        //3 - 8bit; [0;255]
                        //4 - 32bit (float); [-1.0;1.0]

                        ShortBuffer out = output.order(ByteOrder.nativeOrder()).asShortBuffer();
                        short[] buf = new short[out.limit()];
                        out.get(buf, 0, out.limit());

                        ShortBuffer shBuff = ShortBuffer.wrap(buf);
                        float variable;
                        if (pcmType == 2 || pcmType == 0) {
                            variable = 3276.8f;
                        } else
                        if (pcmType == 3) {
                            variable = 25.5f;
                        } else
                            variable = 0.1f;

                        float samples = 0;
                        for (int i = 0; i < buf.length; i++) {
                            if (shBuff.get(i) < 0)
                                samples += -((float) shBuff.get(i) / variable);
                            else
                                samples += ((float) shBuff.get(i) / variable);
                        }

                        if (samples > 0.01)
                            sampleArray.add(samples);

                        codec.releaseOutputBuffer(index, false);

                        if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                            LogManager.d("MediaCodec", "BUFFER_FLAG_END_OF_STREAM");
                            codec.stop();
                            codec.release();
                            long num = 0;
                            if (sampleArray != null) {
                                int sampleRate = sampleArray.size() / 50;
                                if (sampleRate != 0) {
                                    for (int i = 0; i < sampleArray.size(); i++) {
                                        if (i % sampleRate == 0) {
                                            squashedArray.add(Utils.longToInt(num));
                                            num = 0;
                                        }
                                        if (sampleArray.get(i) < 0)
                                            num += -sampleArray.get(i);
                                        else
                                            num += sampleArray.get(i);
                                    }
                                    squashedArray.add(Utils.longToInt(num));
                                } else squashedArray.add(0);
                            } else squashedArray.add(0);
                            if ((voiceWaveData.get(file.getPath()) == null || voiceWaveData.get(file.getPath()).isEmpty()) && !squashedArray.isEmpty()) {
                                voiceWaveData.put(file.getPath(), squashedArray);
                                view.updateVisualizer(squashedArray);
                            }
                            waveInProgress.remove(file.getPath());
                        } else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                            LogManager.d("MediaCodec", "BUFFER_FLAG_CODEC_CONFIG");
                        } else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                            LogManager.d("MediaCodec", "BUFFER_FLAG_KEY_FRAME");
                        } else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) {
                            LogManager.d("MediaCodec", "BUFFER_FLAG_PARTIAL_FRAME");
                        }
                    }

                    @Override
                    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                        LogManager.e("MediaCodec", e.getDiagnosticInfo());
                    }

                    @Override
                    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {

                    }
                });

                codec.configure(format, null, null, 0);
                codec.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

