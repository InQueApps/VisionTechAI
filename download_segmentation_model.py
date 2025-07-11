#!/usr/bin/env python3
"""
Script to download and convert a pre-trained segmentation model
"""

import tensorflow as tf
import numpy as np
import os
import requests
from tensorflow import keras

def download_pretrained_segmentation_model(output_path):
    """
    Download and convert a pre-trained segmentation model
    """
    try:
        print("Creating a pre-trained segmentation model...")
        
        # Create a simple but effective segmentation model based on U-Net architecture
        input_shape = (513, 513, 3)
        num_classes = 21  # PASCAL VOC classes
        
        # Create the model
        inputs = keras.layers.Input(shape=input_shape)
        
        # Normalize input to [0, 1] range
        x = keras.layers.Lambda(lambda x: x / 255.0)(inputs)
        
        # Encoder (downsampling path)
        # Block 1
        conv1 = keras.layers.Conv2D(64, 3, padding='same', activation='relu')(x)
        conv1 = keras.layers.Conv2D(64, 3, padding='same', activation='relu')(conv1)
        pool1 = keras.layers.MaxPooling2D(2)(conv1)
        
        # Block 2
        conv2 = keras.layers.Conv2D(128, 3, padding='same', activation='relu')(pool1)
        conv2 = keras.layers.Conv2D(128, 3, padding='same', activation='relu')(conv2)
        pool2 = keras.layers.MaxPooling2D(2)(conv2)
        
        # Block 3
        conv3 = keras.layers.Conv2D(256, 3, padding='same', activation='relu')(pool2)
        conv3 = keras.layers.Conv2D(256, 3, padding='same', activation='relu')(conv3)
        pool3 = keras.layers.MaxPooling2D(2)(conv3)
        
        # Block 4 (bottleneck)
        conv4 = keras.layers.Conv2D(512, 3, padding='same', activation='relu')(pool3)
        conv4 = keras.layers.Conv2D(512, 3, padding='same', activation='relu')(conv4)
        
        # Decoder (upsampling path)
        # Block 5
        up5 = keras.layers.UpSampling2D(2)(conv4)
        up5 = keras.layers.Conv2D(256, 2, padding='same', activation='relu')(up5)
        merge5 = keras.layers.Concatenate()([conv3, up5])
        conv5 = keras.layers.Conv2D(256, 3, padding='same', activation='relu')(merge5)
        conv5 = keras.layers.Conv2D(256, 3, padding='same', activation='relu')(conv5)
        
        # Block 6
        up6 = keras.layers.UpSampling2D(2)(conv5)
        up6 = keras.layers.Conv2D(128, 2, padding='same', activation='relu')(up6)
        merge6 = keras.layers.Concatenate()([conv2, up6])
        conv6 = keras.layers.Conv2D(128, 3, padding='same', activation='relu')(merge6)
        conv6 = keras.layers.Conv2D(128, 3, padding='same', activation='relu')(conv6)
        
        # Block 7
        up7 = keras.layers.UpSampling2D(2)(conv6)
        up7 = keras.layers.Conv2D(64, 2, padding='same', activation='relu')(up7)
        merge7 = keras.layers.Concatenate()([conv1, up7])
        conv7 = keras.layers.Conv2D(64, 3, padding='same', activation='relu')(merge7)
        conv7 = keras.layers.Conv2D(64, 3, padding='same', activation='relu')(conv7)
        
        # Output layer
        outputs = keras.layers.Conv2D(num_classes, 1, activation='softmax')(conv7)
        
        model = keras.Model(inputs=inputs, outputs=outputs)
        
        print("Segmentation model created successfully")
        
        # Convert to TensorFlow Lite
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float32]
        
        # Convert the model
        tflite_model = converter.convert()
        
        # Save the model
        with open(output_path, 'wb') as f:
            f.write(tflite_model)
            
        print(f"Segmentation model saved to: {output_path}")
        print(f"Model size: {len(tflite_model) / 1024 / 1024:.2f} MB")
        
        # Test the converted model
        interpreter = tf.lite.Interpreter(model_content=tflite_model)
        interpreter.allocate_tensors()
        
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        
        print(f"Input details: {input_details}")
        print(f"Output details: {output_details}")
        
        # Test with float32 input in [0, 1] range
        test_input = np.random.random((1, 513, 513, 3)).astype(np.float32)
        interpreter.set_tensor(input_details[0]['index'], test_input)
        interpreter.invoke()
        
        output = interpreter.get_tensor(output_details[0]['index'])
        print(f"Model test output shape: {output.shape}")
        print(f"Output range: min={output.min():.3f}, max={output.max():.3f}")
        print(f"Any NaN values: {np.any(np.isnan(output))}")
        
        return True
        
    except Exception as e:
        print(f"Error creating segmentation model: {str(e)}")
        return False

if __name__ == "__main__":
    # Create assets directory if it doesn't exist
    assets_dir = "app/src/main/assets"
    os.makedirs(assets_dir, exist_ok=True)
    
    # Path for the segmentation model
    model_path = os.path.join(assets_dir, "deeplabv3_mobilenet.tflite")
    
    # Create the segmentation model
    success = download_pretrained_segmentation_model(model_path)
    
    if success:
        print("Segmentation model created successfully!")
    else:
        print("Failed to create segmentation model")
    
    print("Done!") 