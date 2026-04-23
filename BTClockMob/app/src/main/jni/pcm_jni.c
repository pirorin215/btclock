#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <ctype.h>
#include <sys/types.h>
#include <unistd.h>
#include "adpcm.h"

// ADPCM-XQ Xtreme Quality IMA-ADPCM WAV Encoder / Decoder Version 0.2
// Copyright (c) 2015 David Bryant. All Rights Reserved.
// Distributed under the BSD Software License (see license.txt)

#define ADPCM_FLAG_NOISE_SHAPING 1
#define ADPCM_FLAG_RAW_OUTPUT 2

#define LOG_TAG "ADPCM_Decoder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// Global JNI environment and jobject pointers for callback
static JavaVM *g_JavaVM;
// static JNIEnv *envObject; // Removed global JNIEnv*
static jobject listenerObject;

typedef struct _RiffChunkHeader {
    char        chunk_id [4];
    uint32_t    chunk_size;
} RiffChunkHeader;

typedef struct _ChunkHeader {
    char        chunk_id [4];
    uint32_t    chunk_size;
} ChunkHeader;

typedef struct _WaveHeader {
    uint16_t    format_tag;
    uint16_t    channels;
    uint32_t    sample_rate;
    uint32_t    bytes_per_second;
    uint16_t    block_align;
    uint16_t    bits_per_sample;
    uint16_t    cb_size;
    uint16_t    valid_bits_per_sample;
    uint32_t    channel_mask;
    uint8_t     sub_format [16];
} WaveHeader;

typedef struct _FactHeader {
    uint32_t    samples_per_channel;
} FactHeader;

#define WAVE_FORMAT_PCM         0x0001
#define WAVE_FORMAT_IMA_ADPCM   0x0011
#define WAVE_FORMAT_EXTENSIBLE  0xFFFE

// Function to convert little-endian to native endianness
static uint32_t little_endian_to_native(uint32_t val) {
    // Assuming native is little-endian for simplicity, or handle byte swapping if big-endian
    return val;
}

// Function to convert native endianness to little-endian
static uint32_t native_to_little_endian(uint32_t val) {
    // Assuming native is little-endian for simplicity, or handle byte swapping if big-endian
    return val;
}


// static functions - these are only visible in this .c file
// for now, until it's clear which are general purpose enough to go into adpcmlib.cpp

static void write_pcm_wav_header (FILE *f, int channels, int sample_rate, int bits_per_sample, size_t pcm_data_size) {
    RiffChunkHeader riff_header;
    ChunkHeader fmt_chunk, data_chunk;
    WaveHeader wave_header;

    memcpy (riff_header.chunk_id, "RIFF", 4);
    riff_header.chunk_size = native_to_little_endian (36 + pcm_data_size); // 36 bytes for header + data size
    
    memcpy (fmt_chunk.chunk_id, "fmt ", 4);
    fmt_chunk.chunk_size = native_to_little_endian (16); // PCM fmt chunk is 16 bytes

    wave_header.format_tag = native_to_little_endian (WAVE_FORMAT_PCM);
    wave_header.channels = native_to_little_endian (channels);
    wave_header.sample_rate = native_to_little_endian (sample_rate);
    wave_header.bits_per_sample = native_to_little_endian (bits_per_sample);
    wave_header.block_align = native_to_little_endian (channels * bits_per_sample / 8);
    wave_header.bytes_per_second = native_to_little_endian (wave_header.sample_rate * wave_header.block_align);
    
    memcpy (data_chunk.chunk_id, "data", 4);
    data_chunk.chunk_size = native_to_little_endian (pcm_data_size);

    fwrite (&riff_header, 1, sizeof(RiffChunkHeader), f);
    fwrite ("WAVE", 1, 4, f);
    fwrite (&fmt_chunk, 1, sizeof(ChunkHeader), f);
    fwrite (&wave_header, 1, 16, f); // 16 bytes for PCM wave header
    fwrite (&data_chunk, 1, sizeof(ChunkHeader), f);
}

static void write_adpcm_wav_header (FILE *f, int channels, int sample_rate, size_t adpcm_data_size, int samples_per_block) {
    RiffChunkHeader riff_header;
    ChunkHeader fmt_chunk, fact_chunk, data_chunk;
    WaveHeader wave_header;
    FactHeader fact_data;

    memcpy (riff_header.chunk_id, "RIFF", 4);
    riff_header.chunk_size = native_to_little_endian (36 + 8 + 8 + adpcm_data_size); // RIFF + WAVE + fmt + fact + data chunks
    
    memcpy (fmt_chunk.chunk_id, "fmt ", 4);
    fmt_chunk.chunk_size = native_to_little_endian (20); // ADPCM fmt chunk is 20 bytes

    wave_header.format_tag = native_to_little_endian (WAVE_FORMAT_IMA_ADPCM);
    wave_header.channels = native_to_little_endian (channels);
    wave_header.sample_rate = native_to_little_endian (sample_rate);
    wave_header.bits_per_sample = native_to_little_endian (4); // ADPCM has 4 bits per sample
    wave_header.block_align = native_to_little_endian (samples_per_block * channels / 2); // each sample is 4 bits, so 2 samples per byte
    wave_header.bytes_per_second = native_to_little_endian (wave_header.sample_rate * wave_header.block_align);
    wave_header.cb_size = native_to_little_endian (2); // extension size for ADPCM
    wave_header.valid_bits_per_sample = native_to_little_endian (4); // valid bits per sample for ADPCM

    memcpy (fact_chunk.chunk_id, "fact", 4);
    fact_chunk.chunk_size = native_to_little_endian (4);
    fact_data.samples_per_channel = native_to_little_endian (adpcm_data_size * 2 / channels); // total samples in ADPCM data

    memcpy (data_chunk.chunk_id, "data", 4);
    data_chunk.chunk_size = native_to_little_endian (adpcm_data_size);

    fwrite (&riff_header, 1, sizeof(RiffChunkHeader), f);
    fwrite ("WAVE", 1, 4, f);
    fwrite (&fmt_chunk, 1, sizeof(ChunkHeader), f);
    fwrite (&wave_header, 1, 20, f); // 20 bytes for ADPCM wave header
    fwrite (&fact_chunk, 1, sizeof(ChunkHeader), f);
    fwrite (&fact_data, 1, sizeof(FactHeader), f);
    fwrite (&data_chunk, 1, sizeof(ChunkHeader), f);
}

// Callback for progress updates to Java
// void update_progress(JNIEnv *current_env, int progress) {
//     if (current_env == NULL || listenerObject == NULL) {
//         LOGE("update_progress: JNIEnv or jobject is NULL");
//         return;
//     }

//     jclass clazz = (*current_env)->GetObjectClass(current_env, listenerObject);
//     if (clazz == NULL) {
//         LOGE("update_progress: Could not get class for listener object");
//         return;
//     }

//     jmethodID methodId = (*current_env)->GetMethodID(current_env, clazz, "onProgressUpdate", "(I)V");
//     if (methodId == NULL) {
//         LOGE("update_progress: Could not find method onProgressUpdate(I)");
//         return;
//     }

//     (*current_env)->CallVoidMethod(current_env, listenerObject, methodId, progress);
// }

// JNI function to decode ADPCM to PCM
JNIEXPORT jboolean JNICALL Java_com_pirorin215_fastrecmob_adpcm_AdpcmDecoder_decodeToPCM
  (JNIEnv *env, jobject obj, jstring inputFileName, jstring outputFileName, jobject progressListener) {
    const char *input_path = (*env)->GetStringUTFChars(env, inputFileName, 0);
    const char *output_path = (*env)->GetStringUTFChars(env, outputFileName, 0);

    FILE *in_file = NULL;
    FILE *out_file = NULL;

    RiffChunkHeader riff_header;
    char wave_id[4];
    ChunkHeader chunk_header;
    WaveHeader wave_header;
    FactHeader fact_header = {0}; // Initialize to prevent uninitialized data use

    int channels = 0;
    int sample_rate = 0;
    int adpcm_block_align = 0;
    size_t adpcm_data_size = 0;
    int samples_per_block = 0;

    jboolean result = JNI_FALSE;

    if (progressListener == NULL) {
        LOGD("decodeToPCM: progressListener is NULL.");
    } else {
        LOGD("decodeToPCM: progressListener is NOT NULL.");
    }

    // envObject = env; // No longer needed as JNIEnv* is passed directly
    if (progressListener == NULL) {
        LOGD("decodeToPCM: progressListener is NULL.");
    } else {
        LOGD("decodeToPCM: progressListener is NOT NULL.");
    }
    if (progressListener != NULL) {
        listenerObject = (*env)->NewGlobalRef(env, progressListener);
        if (listenerObject == NULL) {
            LOGE("Failed to create global reference for listenerObject.");
            goto end;
        }
    } else {
        listenerObject = NULL;
    }

    LOGD("Attempting to decode: %s to %s", input_path, output_path);


    in_file = fopen(input_path, "rb");
    if (!in_file) {
        LOGE("Failed to open input file: %s", input_path);
        goto end;
    }

    out_file = fopen(output_path, "wb");
    if (!out_file) {
        LOGE("Failed to open output file: %s", output_path);
        goto end;
    }

    // Write a dummy WAV header that will be filled in later
    char dummy_header[44] = {0};
    fwrite(dummy_header, 1, sizeof(dummy_header), out_file);

    // Read RIFF header
    if (fread(&riff_header, 1, sizeof(RiffChunkHeader), in_file) != sizeof(RiffChunkHeader) ||
        strncmp(riff_header.chunk_id, "RIFF", 4) != 0) {
        LOGE("Not a RIFF file");
        goto end;
    }
    LOGD("RIFF chunk_id: %c%c%c%c, chunk_size: %u", riff_header.chunk_id[0], riff_header.chunk_id[1], riff_header.chunk_id[2], riff_header.chunk_id[3], little_endian_to_native(riff_header.chunk_size));


    // Read WAVE ID
    if (fread(&wave_id, 1, 4, in_file) != 4 || strncmp(wave_id, "WAVE", 4) != 0) {
        LOGE("Not a WAVE file");
        goto end;
    }
    LOGD("WAVE ID: %c%c%c%c", wave_id[0], wave_id[1], wave_id[2], wave_id[3]);

    // Read fmt chunk
    uint32_t fmt_chunk_size_actual = 0;
    if (fread(&chunk_header, 1, sizeof(ChunkHeader), in_file) != sizeof(ChunkHeader) ||
        strncmp(chunk_header.chunk_id, "fmt ", 4) != 0) {
        LOGE("Missing fmt chunk or not 'fmt '");
        goto end;
    }
    fmt_chunk_size_actual = little_endian_to_native(chunk_header.chunk_size);
    LOGD("fmt chunk_id: %c%c%c%c, size: %u", chunk_header.chunk_id[0], chunk_header.chunk_id[1], chunk_header.chunk_id[2], chunk_header.chunk_id[3], fmt_chunk_size_actual);

    if (fmt_chunk_size_actual < 16) {
        LOGE("fmt chunk too small (%u bytes), expected at least 16", fmt_chunk_size_actual);
        goto end;
    }

    // Read base 16 bytes of WaveHeader
    if (fread(&wave_header, 1, 16, in_file) != 16) {
        LOGE("Failed to read base 16 bytes of wave header");
        goto end;
    }
    
    // Bytes read so far from fmt chunk
    size_t bytes_read_in_fmt = 16;

    // Read ADPCM specific extension fields if present
    if (little_endian_to_native(wave_header.format_tag) == WAVE_FORMAT_IMA_ADPCM) {
        // Check if there are enough bytes for cb_size (2 bytes)
        if (fmt_chunk_size_actual - bytes_read_in_fmt >= 2) {
            if (fread(&wave_header.cb_size, 1, 2, in_file) != 2) {
                LOGE("Failed to read cbSize for ADPCM");
                goto end;
            }
            bytes_read_in_fmt += 2;
        } else {
            LOGE("ADPCM fmt chunk extension (cbSize) too small. Expected at least 2 bytes, found %zu.", fmt_chunk_size_actual - bytes_read_in_fmt);
            // Even if too small, we might continue if we can skip remaining bytes
        }

        if (little_endian_to_native(wave_header.cb_size) >= 2) {
            // Check if there are enough bytes for valid_bits_per_sample (2 bytes)
            if (fmt_chunk_size_actual - bytes_read_in_fmt >= 2) {
                if (fread(&wave_header.valid_bits_per_sample, 1, 2, in_file) != 2) {
                    LOGE("Failed to read valid_bits_per_sample for ADPCM");
                    goto end;
                }
                bytes_read_in_fmt += 2;
            } else {
                LOGE("ADPCM fmt chunk extension (valid_bits_per_sample) too small. Expected at least 2 bytes, found %zu.", fmt_chunk_size_actual - bytes_read_in_fmt);
            }
        }
    } else {
        LOGE("Unsupported audio format tag: 0x%04X", little_endian_to_native(wave_header.format_tag));
        goto end;
    }

    // Skip any extra fmt bytes that were not read by WaveHeader or its extensions
    if (fmt_chunk_size_actual > bytes_read_in_fmt) {
        fseek(in_file, fmt_chunk_size_actual - bytes_read_in_fmt, SEEK_CUR);
        LOGD("Skipped %zu extra bytes in fmt chunk.", fmt_chunk_size_actual - bytes_read_in_fmt);
    }

    channels = little_endian_to_native(wave_header.channels);
    sample_rate = little_endian_to_native(wave_header.sample_rate);
    adpcm_block_align = little_endian_to_native(wave_header.block_align);

    // Find data chunk
    while (fread(&chunk_header, 1, sizeof(ChunkHeader), in_file) == sizeof(ChunkHeader)) {
        LOGD("Found chunk: %c%c%c%c, size: %u", chunk_header.chunk_id[0], chunk_header.chunk_id[1], chunk_header.chunk_id[2], chunk_header.chunk_id[3], little_endian_to_native(chunk_header.chunk_size));
        if (strncmp(chunk_header.chunk_id, "data", 4) == 0) {
            adpcm_data_size = little_endian_to_native(chunk_header.chunk_size);
            break;
        } else if (strncmp(chunk_header.chunk_id, "fact", 4) == 0) {
            // Read and skip fact chunk
            uint32_t fact_chunk_size = little_endian_to_native(chunk_header.chunk_size);
            if (fact_chunk_size >= sizeof(FactHeader)) { // Ensure fact chunk is large enough for FactHeader
                if (fread(&fact_header, 1, sizeof(FactHeader), in_file) != sizeof(FactHeader)) {
                    LOGE("Failed to read FactHeader from fact chunk");
                    goto end;
                }
                // Skip any extra bytes in fact chunk
                if (fact_chunk_size > sizeof(FactHeader)) {
                    fseek(in_file, fact_chunk_size - sizeof(FactHeader), SEEK_CUR);
                }
            } else {
                LOGE("fact chunk too small (%u bytes), expected at least %zu", fact_chunk_size, sizeof(FactHeader));
                fseek(in_file, fact_chunk_size, SEEK_CUR); // Skip the malformed fact chunk
            }
        } else {
            // Skip unknown chunk
            fseek(in_file, little_endian_to_native(chunk_header.chunk_size), SEEK_CUR);
        }
    }

    if (adpcm_data_size == 0) {
        LOGE("No data chunk found or data size is 0 after parsing all chunks.");
        goto end;
    }

    // This calculation is critical for buffer allocation and must always be performed.
    // The formula for IMA ADPCM samples per block is: ((BlockAlign / NumChannels) - 4) * 2 + 1
    if (adpcm_block_align > (4 * channels) && channels > 0) {
        samples_per_block = ((adpcm_block_align / channels) - 4) * 2 + 1;
    } else {
        LOGE("Invalid adpcm_block_align (%d) or channels (%d) for samples_per_block calculation.", adpcm_block_align, channels);
        goto end;
    }

    // ... (rest of the code for total_pcm_samples_per_channel calculation)
    size_t total_pcm_samples_per_channel = 0;
    // Only use fact_header if it was successfully read and is valid
    if (fact_header.samples_per_channel != 0 && little_endian_to_native(fact_header.samples_per_channel) > 0) {
        total_pcm_samples_per_channel = little_endian_to_native(fact_header.samples_per_channel);
        LOGD("Using samples_per_channel from fact chunk: %zu", total_pcm_samples_per_channel);
    } else {
        // Fallback calculation if fact chunk was not present or invalid
        if (adpcm_data_size > 0 && adpcm_block_align > 0) {
            total_pcm_samples_per_channel = (adpcm_data_size / adpcm_block_align) * samples_per_block;
            LOGD("Calculating samples_per_channel fallback: %zu", total_pcm_samples_per_channel);
        } else {
            LOGE("Invalid adpcm_data_size (%zu) or adpcm_block_align (%d) for samples_per_channel fallback calculation.", adpcm_data_size, adpcm_block_align);
            goto end;
        }
    }

    // Decoding loop
    // buffer for one ADPCM block
    uint8_t *in_block_buffer = (uint8_t *) malloc(adpcm_block_align);
    // buffer for decoded PCM data of one block
    int16_t *out_block_buffer = (int16_t *) malloc(samples_per_block * channels * sizeof(int16_t));
    
    if (!in_block_buffer || !out_block_buffer) {
        LOGE("Memory allocation failed for buffers.");
        goto end;
    }

    size_t bytes_read = 0;
    size_t total_adpcm_data_read = 0;
    int progress_percent = 0;

    while (total_adpcm_data_read < adpcm_data_size) {
        bytes_read = fread(in_block_buffer, 1, adpcm_block_align, in_file);
        if (bytes_read == 0) {
            LOGE("Error reading ADPCM data block or EOF reached unexpectedly.");
            break;
        }

        int decoded_samples = adpcm_decode_block(out_block_buffer, in_block_buffer, bytes_read, channels);
        if (decoded_samples == 0) {
            LOGE("ADPCM decode block failed.");
            goto end; // or handle error more gracefully
        }

        fwrite(out_block_buffer, sizeof(int16_t), decoded_samples * channels, out_file);

        total_adpcm_data_read += bytes_read;
        int current_progress = (int)(((float)total_adpcm_data_read / adpcm_data_size) * 100);
        if (current_progress > progress_percent) {
            progress_percent = current_progress;
            // update_progress(env, current_progress);
        }
    }

    LOGD("Decoding complete. Total ADPCM data read: %zu, Total expected: %zu", total_adpcm_data_read, adpcm_data_size);

    // Get the final PCM data size and rewind to write the header
    long pcm_data_size = ftell(out_file) - 44;
    fseek(out_file, 0, SEEK_SET);

    // Write the actual WAV header
    int bits_per_sample = 16;
    write_pcm_wav_header(out_file, channels, sample_rate, bits_per_sample, pcm_data_size);

    // Ensure all data is written to disk before returning
    fflush(out_file);
    int fd = fileno(out_file);
    fsync(fd);

    result = JNI_TRUE;

end:
    if (in_file) fclose(in_file);
    if (out_file) fclose(out_file);
    if (in_block_buffer) free(in_block_buffer);
    if (out_block_buffer) free(out_block_buffer);

    (*env)->ReleaseStringUTFChars(env, inputFileName, input_path);
    (*env)->ReleaseStringUTFChars(env, outputFileName, output_path);
    // Release the global reference if it was created
    if (listenerObject != NULL) {
        (*env)->DeleteGlobalRef(env, listenerObject);
        listenerObject = NULL; // Clear the global reference
    }
    return result;
}

// Test function to decode ADPCM file and return bytes read
// Returns: >= 0 on success (number of bytes read), < 0 on failure
JNIEXPORT jint JNICALL Java_com_pirorin215_fastrecmob_viewModel_MainViewModel_testDecodeAdpcmNative
  (JNIEnv *env, jobject obj, jstring inputFileName) {

    const char *input_path = (*env)->GetStringUTFChars(env, inputFileName, 0);
    FILE *in_file = NULL;
    uint8_t *in_block_buffer = NULL;
    int16_t *out_block_buffer = NULL;
    int result = -1;  // Default to failure
    size_t total_adpcm_data_read = 0;
    size_t adpcm_data_size = 0;
    int channels = 1;
    int adpcm_block_align = 256;
    int sample_rate = 0;

    LOGD("Testing ADPCM decode for: %s", input_path);

    in_file = fopen(input_path, "rb");
    if (in_file == NULL) {
        LOGE("Failed to open input file: %s", input_path);
        goto end;
    }

    // Read and verify RIFF header
    RiffChunkHeader riff_header;
    if (fread(&riff_header, 1, sizeof(RiffChunkHeader), in_file) != sizeof(RiffChunkHeader)) {
        LOGE("Failed to read RIFF header.");
        goto end;
    }

    if (memcmp(riff_header.chunk_id, "RIFF", 4) != 0) {
        LOGE("Not a RIFF file.");
        goto end;
    }

    // Skip "WAVE" ID
    char wave_id[4];
    if (fread(wave_id, 1, 4, in_file) != 4) {
        LOGE("Failed to read WAVE ID.");
        goto end;
    }

    if (memcmp(wave_id, "WAVE", 4) != 0) {
        LOGE("Not a WAVE file.");
        goto end;
    }

    // Read fmt chunk
    ChunkHeader fmt_chunk;
    if (fread(&fmt_chunk, 1, sizeof(ChunkHeader), in_file) != sizeof(ChunkHeader)) {
        LOGE("Failed to read fmt chunk header.");
        goto end;
    }

    if (memcmp(fmt_chunk.chunk_id, "fmt ", 4) != 0) {
        LOGE("Expected fmt chunk.");
        goto end;
    }

    // Read wave format
    uint16_t format_tag;
    uint16_t block_align;
    uint16_t bits_per_sample;

    fread(&format_tag, 1, sizeof(uint16_t), in_file);
    fread(&channels, 1, sizeof(uint16_t), in_file);
    fread(&sample_rate, 1, sizeof(uint32_t), in_file);
    // Skip bytes_per_second
    fseek(in_file, 4, SEEK_CUR);
    fread(&block_align, 1, sizeof(uint16_t), in_file);
    fread(&bits_per_sample, 1, sizeof(uint16_t), in_file);

    adpcm_block_align = block_align;

    // Skip remaining fmt bytes
    int remaining_fmt = little_endian_to_native(fmt_chunk.chunk_size) - 16;
    if (remaining_fmt > 0) {
        fseek(in_file, remaining_fmt, SEEK_CUR);
    }

    LOGD("Audio format: channels=%d, sample_rate=%d, block_align=%d", channels, sample_rate, adpcm_block_align);

    // Find data chunk (skip other chunks like fact)
    int found_data = 0;
    while (!found_data) {
        ChunkHeader chunk;
        if (fread(&chunk, 1, sizeof(ChunkHeader), in_file) != sizeof(ChunkHeader)) {
            LOGE("Failed to read chunk header.");
            goto end;
        }

        if (memcmp(chunk.chunk_id, "data", 4) == 0) {
            adpcm_data_size = little_endian_to_native(chunk.chunk_size);
            found_data = 1;
            LOGD("Found data chunk, size: %zu bytes", adpcm_data_size);
        } else {
            // Skip this chunk
            uint32_t chunk_size = little_endian_to_native(chunk.chunk_size);
            LOGD("Skipping chunk: %.4s, size: %u", chunk.chunk_id, chunk_size);
            fseek(in_file, chunk_size, SEEK_CUR);
        }
    }

    // Allocate buffers
    in_block_buffer = (uint8_t *)malloc(adpcm_block_align);
    out_block_buffer = (int16_t *)malloc(adpcm_block_align * 4);  // Worst case expansion

    if (in_block_buffer == NULL || out_block_buffer == NULL) {
        LOGE("Failed to allocate buffers.");
        goto end;
    }

    // Decode all blocks
    while (total_adpcm_data_read < adpcm_data_size) {
        size_t bytes_to_read = adpcm_block_align;
        if (total_adpcm_data_read + bytes_to_read > adpcm_data_size) {
            bytes_to_read = adpcm_data_size - total_adpcm_data_read;
        }

        size_t bytes_read = fread(in_block_buffer, 1, bytes_to_read, in_file);
        if (bytes_read == 0) {
            LOGD("EOF reached during decode test.");
            break;
        }

        int decoded_samples = adpcm_decode_block(out_block_buffer, in_block_buffer, bytes_read, channels);
        if (decoded_samples == 0) {
            LOGE("ADPCM decode block failed at offset %zu.", total_adpcm_data_read);
            result = -1;
            goto end;
        }

        total_adpcm_data_read += bytes_read;
    }

    LOGD("ADPCM decode test successful. Total ADPCM data read: %zu, Total expected: %zu",
            total_adpcm_data_read, adpcm_data_size);
    result = (int)total_adpcm_data_read;

end:
    if (in_file) fclose(in_file);
    if (in_block_buffer) free(in_block_buffer);
    if (out_block_buffer) free(out_block_buffer);

    (*env)->ReleaseStringUTFChars(env, inputFileName, input_path);
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_JavaVM = vm;
    LOGD("JNI_OnLoad called.");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    LOGD("JNI_OnUnload called.");
    g_JavaVM = NULL;
    // envObject = NULL; // No longer needed
    listenerObject = NULL;
}
