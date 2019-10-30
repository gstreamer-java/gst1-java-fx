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
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
 *
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
    private final ObjectProperty<Image> image;
    private final AtomicReference<Sample> pending;
    private final NewSampleListener newSampleListener;
    private final NewPrerollListener newPrerollListener;
    
    private Sample activeSample;
    private Buffer activeBuffer;
    
    public FXImageSink() {
        this(new AppSink("FXImageSink"));
    }
    
    public FXImageSink(AppSink sink) {
        this.sink = sink;
        sink.set("emit-signals", true);
        newSampleListener = new NewSampleListener();
        newPrerollListener = new NewPrerollListener();
        sink.connect(newSampleListener);
        sink.connect(newPrerollListener);
        sink.setCaps(Caps.fromString(DEFAULT_CAPS));
        image = new SimpleObjectProperty<>();
        pending = new AtomicReference<>();
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
    
    public ObjectProperty<Image> imageProperty() {
        return image;
    }
    
    public AppSink getElement() {
        return sink;
    }
    
    
    private class NewSampleListener implements AppSink.NEW_SAMPLE {

        @Override
        public FlowReturn newSample(AppSink appsink) {
            Sample s = appsink.pullSample();
            s = pending.getAndSet(s);
            if (s != null) {
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
                s.dispose();
            }
            Platform.runLater(() -> updateImage());
            return FlowReturn.OK;
        }
        
    }
    
}
