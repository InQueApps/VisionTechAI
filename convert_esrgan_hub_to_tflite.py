#!/usr/bin/env python3
"""
Download ESRGAN-TF2 from TensorFlow Hub, save as SavedModel, and convert to TFLite.
"""
import tensorflow as tf
import tensorflow_hub as hub
import os

# Model URL from TensorFlow Hub
MODEL_URL = "https://kaggle.com/models/kaggle/esrgan-tf2/frameworks/TensorFlow2/variations/esrgan-tf2/versions/1"
SAVED_MODEL_DIR = "esrgan_saved_model"
TFLITE_MODEL_PATH = "app/src/main/assets/esrgan.tflite"

# 1. Download and save as SavedModel
print("Loading ESRGAN model from TensorFlow Hub...")
model = hub.load(MODEL_URL)
tf.saved_model.save(model, SAVED_MODEL_DIR)
print(f"SavedModel exported to {SAVED_MODEL_DIR}")

# 2. Convert SavedModel to TFLite
print("Converting SavedModel to TFLite...")
converter = tf.lite.TFLiteConverter.from_saved_model(SAVED_MODEL_DIR)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()
with open(TFLITE_MODEL_PATH, "wb") as f:
    f.write(tflite_model)
print(f"TFLite model saved to {TFLITE_MODEL_PATH}") 