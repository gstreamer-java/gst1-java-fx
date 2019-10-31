/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2019 Neil C Smith.
 * 
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 3 only, as
 * published by the Free Software Foundation.
 * 
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * version 3 for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License version 3
 * along with this work; if not, see http://www.gnu.org/licenses/
 *
 */
package org.freedesktop.gstreamer.fx;

import java.util.concurrent.atomic.AtomicReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.image.Image;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;

/**
 * A wrapper connecting a GStreamer AppSink and a JavaFX Image, making use of
 * {@link PixelBuffer} to directly access the native GStreamer pixel data.
 * <p>
 * Use {@link #imageProperty()} to access the JavaFX image. The Image should
 * only be used on the JavaFX application thread, and is only valid while it is
 * the current property value. Using the Image when it is no longer the current
 * property value may cause errors or crashes.
 */
public class FXImageSink {

    private final static String DEFAULT_CAPS;

    static {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            DEFAULT_CAPS = "video/x-raw, format=BGRx";
        } else {
            DEFAULT_CAPS = "video/x-raw, format=xRGB";
        }
    }

    private final AppSink sink;
    private final ReadOnlyObjectWrapper<Image> image;
    private final AtomicReference<Sample> pending;
    private final NewSampleListener newSampleListener;
    private final NewPrerollListener newPrerollListener;

    private Sample activeSample;
    private Buffer activeBuffer;

    private int requestWidth;
    private int requestHeight;
    private int requestRate;

    /**
     * Create an FXImageSink. A new AppSink element will be created that can be
     * accessed using {@link #getSinkElement()}.
     */
    public FXImageSink() {
        this(new AppSink("FXImageSink"));
    }

    /**
     * Create an FXImageSink wrapping the provided AppSink element.
     *
     * @param sink AppSink element
     */
    public FXImageSink(AppSink sink) {
        this.sink = sink;
        sink.set("emit-signals", true);
        newSampleListener = new NewSampleListener();
        newPrerollListener = new NewPrerollListener();
        sink.connect(newSampleListener);
        sink.connect(newPrerollListener);
        sink.setCaps(Caps.fromString(DEFAULT_CAPS));
        image = new ReadOnlyObjectWrapper<>();
        pending = new AtomicReference<>();
    }

    /**
     * Property wrapping the current video frame as a JavaFX {@link Image}. The
     * Image should only be accessed on the JavaFX application thread. Use of
     * the Image when it is no longer the current value of this property may
     * cause errors or crashes.
     *
     * @return image property for current video frame
     */
    public ReadOnlyObjectProperty<Image> imageProperty() {
        return image.getReadOnlyProperty();
    }

    /**
     * Get access to the AppSink element this class wraps.
     *
     * @return AppSink element
     */
    public AppSink getSinkElement() {
        return sink;
    }

    /**
     * Clear any image and dispose of underlying native buffers. Can be called
     * from any thread, but clearing will happen asynchronously if not called on
     * JavaFX application thread.
     */
    public void clear() {
        if (Platform.isFxApplicationThread()) {
            clearImage();
        } else {
            Platform.runLater(() -> clearImage());
        }
    }

    /**
     * Request the given frame size for each video frame. This will set up the
     * Caps on the wrapped AppSink. Values are in pixels. A value of zero or
     * less will result in the value being omitted from the Caps.
     *
     * @param width pixel width
     * @param height pixel height
     * @return this for chaining
     */
    public FXImageSink requestFrameSize(int width, int height) {
        this.requestWidth = width;
        this.requestHeight = height;
        sink.setCaps(Caps.fromString(buildCapsString()));
        return this;
    }

    /**
     * Request the given frame rate. This will set up the Caps on the wrapped
     * AppSink. Value is in frames per second. A value of zero or less will
     * result in the value being omitted from the Caps.
     *
     * @param rate frame rate in frames per second
     * @return this for chaining
     */
    public FXImageSink requestFrameRate(double rate) {
        requestRate = (int) Math.round(rate);
        sink.setCaps(Caps.fromString(buildCapsString()));
        return this;
    }

    private String buildCapsString() {
        if (requestWidth < 1 && requestHeight < 1 && requestRate < 1) {
            return DEFAULT_CAPS;
        }
        StringBuilder sb = new StringBuilder(DEFAULT_CAPS);
        if (requestWidth > 0) {
            sb.append(",width=");
            sb.append(requestWidth);
        }
        if (requestHeight > 0) {
            sb.append(",height=");
            sb.append(requestHeight);
        }
        if (requestRate > 0) {
            sb.append(",framerate=");
            sb.append(requestRate);
            sb.append("/1");
        }
        return sb.toString();
    }

    private void updateImage() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on FX application thread");
        }
        Sample newSample = pending.getAndSet(null);
        if (newSample == null) {
            return;
        }
        Sample oldSample = activeSample;
        Buffer oldBuffer = activeBuffer;

        activeSample = newSample;
        Structure capsStruct = newSample.getCaps().getStructure(0);
        int width = capsStruct.getInteger("width");
        int height = capsStruct.getInteger("height");
        activeBuffer = newSample.getBuffer();

        PixelBuffer<ByteBuffer> pixelBuffer = new PixelBuffer(width, height,
                activeBuffer.map(false), PixelFormat.getByteBgraPreInstance());
        WritableImage img = new WritableImage(pixelBuffer);
        image.set(img);

        if (oldBuffer != null) {
            oldBuffer.unmap();
        }
        if (oldSample != null) {
            oldSample.dispose();
        }

    }

    private void clearImage() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on FX application thread");
        }
        Sample newSample = pending.getAndSet(null);
        if (newSample != null) {
            newSample.dispose();
        }
        image.set(null);
        if (activeBuffer != null) {
            activeBuffer.unmap();
            activeBuffer = null;
        }
        if (activeSample != null) {
            activeSample.dispose();
            activeSample = null;
        }
    }

    private class NewSampleListener implements AppSink.NEW_SAMPLE {

        @Override
        public FlowReturn newSample(AppSink appsink) {
            Sample s = appsink.pullSample();
            s = pending.getAndSet(s);
            if (s != null) {
                // if not null the Sample has not been taken by the application thread so dispose
                s.dispose();
            }
            Platform.runLater(() -> updateImage());
            return FlowReturn.OK;
        }

    }

    private class NewPrerollListener implements AppSink.NEW_PREROLL {

        @Override
        public FlowReturn newPreroll(AppSink appsink) {
            Sample s = appsink.pullPreroll();
            s = pending.getAndSet(s);
            if (s != null) {
                // if not null the Sample has not been taken by the application thread so dispose
                s.dispose();
            }
            Platform.runLater(() -> updateImage());
            return FlowReturn.OK;
        }

    }

}
