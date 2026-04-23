#ifndef ADPCM_H
#define ADPCM_H

#include <stdint.h>
#include <stddef.h> // For size_t

#ifdef __cplusplus
extern "C" {
#endif

// Defined in adpcmlib.cpp
void *adpcm_create_context (int num_channels, int lookahead, int noise_shaping, int32_t initial_deltas [2]);
void adpcm_free_context (void *p);
int adpcm_encode_block (void *p, uint8_t *outbuf, size_t *outbufsize, const int16_t *inbuf, int inbufcount);
int adpcm_decode_block (int16_t *outbuf, const uint8_t *inbuf, size_t inbufsize, int channels);

// Noise shaping constants (if used)
#define NOISE_SHAPING_NONE    0
#define NOISE_SHAPING_STATIC  1
#define NOISE_SHAPING_DYNAMIC 2

#ifdef __cplusplus
}
#endif

#endif // ADPCM_H
