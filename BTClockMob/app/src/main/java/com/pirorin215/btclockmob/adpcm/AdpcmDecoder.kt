package com.pirorin215.btclockmob.adpcm

import android.util.Log

class AdpcmDecoder {

    companion object {
        private const val TAG = "AdpcmDecoder"

        init {
            try {
                System.loadLibrary("adpcm") // "adpcm" is the LOCAL_MODULE name from Android.mk
                Log.d(TAG, "Native library 'adpcm' loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'adpcm': ${e.message}")
            }
        }
    }

    interface ProgressListener {
        fun onProgressUpdate(progress: Int)
    }

    /**
     * Decodes an ADPCM WAV file to a PCM WAV file.
     *
     * @param inputFileName The path to the input ADPCM WAV file.
     * @param outputFileName The path where the output PCM WAV file will be written.
     * @param listener An optional listener to receive progress updates.
     * @return true if decoding was successful, false otherwise.
     */
    external fun decodeToPCM(
        inputFileName: String,
        outputFileName: String,
        listener: ProgressListener?
    ): Boolean
}
