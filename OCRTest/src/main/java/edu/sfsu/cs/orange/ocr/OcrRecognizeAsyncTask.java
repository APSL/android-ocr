/*
 * Copyright 2011 Robert Theis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.sfsu.cs.orange.ocr;

import java.util.ArrayList;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.googlecode.leptonica.android.ReadFile;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Class to send OCR requests to the OCR engine in a separate thread, send a success/failure message,
 * and dismiss the indeterminate progress dialog box. Used for non-continuous mode OCR only.
 */
final class OcrRecognizeAsyncTask extends AsyncTask<Void, Void, Boolean> {

    private CaptureActivity activity;
    private TessBaseAPI baseApi;
    private byte[] data;
    private int width;
    private int height;
    private OcrResult ocrResult;

    OcrRecognizeAsyncTask(CaptureActivity activity, TessBaseAPI baseApi, byte[] data, int width, int height) {
        this.activity = activity;
        this.baseApi = baseApi;
        this.data = data;
        this.width = width;
        this.height = height;
    }

    @Override
    protected Boolean doInBackground(Void... arg0) {
        long start = System.currentTimeMillis();

        ocrResult = new OcrResult();

        // Convert bitmap to Greyscale image
        Bitmap bitmap = activity.getCameraManager().buildLuminanceSource(data, width, height)
                .renderCroppedGreyscaleBitmap();

        if (bitmap == null) {
            return false;
        }

        Size matSize = new Size(bitmap.getWidth(), bitmap.getHeight());
        Mat rectKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(13, 5), new Point(-1, -1));
        // Create a Mat object from bitmap image
        Mat mat = new Mat(bitmap.getWidth(), bitmap.getHeight(), CvType.CV_8UC1);
        Utils.bitmapToMat(bitmap, mat);

        // Convert image to greyscale again
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY);
        // Apply Gaussian Blur filter
        Imgproc.GaussianBlur(mat, mat, new Size(3, 3), 0);
        // A blackhat operator is used to reveal dark regions
        Imgproc.morphologyEx(mat, mat, Imgproc.MORPH_BLACKHAT, rectKernel);

//        Imgproc.adaptiveThreshold(mat, mat, 255,
//                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 7, 2);

        // Rezize mat to fit with bitmap dimensions
        Imgproc.resize(mat, mat, matSize);
        // Convert Mat to Bitmap
        Utils.matToBitmap(mat, bitmap);

        String textResult;
        long timeRequired;
        try {
            baseApi.setImage(ReadFile.readBitmap(bitmap));
            textResult = baseApi.getUTF8Text();

            // Check for failure to recognize text
            if (textResult == null || textResult.equals("")) {
                return false;
            }
            ocrResult = new OcrResult();
            ocrResult.setWordConfidences(baseApi.wordConfidences());
            ocrResult.setMeanConfidence(baseApi.meanConfidence());
            ocrResult.setRegionBoundingBoxes(baseApi.getRegions().getBoxRects());
            ocrResult.setTextlineBoundingBoxes(baseApi.getTextlines().getBoxRects());
            ocrResult.setWordBoundingBoxes(baseApi.getWords().getBoxRects());
            ocrResult.setStripBoundingBoxes(baseApi.getStrips().getBoxRects());

            // Iterate through the results.
            final ResultIterator iterator = baseApi.getResultIterator();
            int[] lastBoundingBox;
            ArrayList<Rect> charBoxes = new ArrayList<>();
            iterator.begin();
            do {
                lastBoundingBox = iterator.getBoundingBox(PageIteratorLevel.RIL_SYMBOL);
                Rect lastRectBox = new Rect(lastBoundingBox[0], lastBoundingBox[1],
                        lastBoundingBox[2], lastBoundingBox[3]);
                charBoxes.add(lastRectBox);
            } while (iterator.next(PageIteratorLevel.RIL_SYMBOL));
            iterator.delete();
            ocrResult.setCharacterBoundingBoxes(charBoxes);
        } catch (RuntimeException e) {
            Log.e("OcrRecognizeAsyncTask", "Caught RuntimeException in request " +
                    "to Tesseract. Setting state to CONTINUOUS_STOPPED.");
            e.printStackTrace();
            try {
                baseApi.clear();
                activity.stopHandler();
            } catch (NullPointerException e1) {
                // Continue
            }
            return false;
        }
        timeRequired = System.currentTimeMillis() - start;
        ocrResult.setBitmap(bitmap);

        // Delete spaces on result and transform characters to upper case
//        textResult = textResult.trim();
//        textResult = textResult.replaceAll("\\s", "");
        textResult = textResult.toUpperCase();

        ocrResult.setText(textResult);
        ocrResult.setRecognitionTimeRequired(timeRequired);
        return true;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        super.onPostExecute(result);

        Handler handler = activity.getHandler();
        if (handler != null) {
            // Send results for single-shot mode recognition.
            if (result) {
                Message message = Message.obtain(handler, R.id.ocr_decode_succeeded, ocrResult);
                message.sendToTarget();
            } else {
                Message message = Message.obtain(handler, R.id.ocr_decode_failed, ocrResult);
                message.sendToTarget();
            }
            activity.getProgressDialog().dismiss();
        }
        if (baseApi != null) {
            baseApi.clear();
        }
    }
}
