LOCAL_PATH:=$(call my-dir)


include $(CLEAR_VARS)

LOCAL_LDLIBS := -llog

#module name and source files
LOCAL_MODULE := adpcm
#LOCAL_C_INCLUDES := adpcmlib.h
LOCAL_SRC_FILES := pcm_jni.c adpcmlib.cpp

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384

include $(BUILD_SHARED_LIBRARY)