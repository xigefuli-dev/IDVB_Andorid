LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := idvb_vpsg
LOCAL_SRC_FILES := vpsg_kernel.cpp
LOCAL_CPPFLAGS := -O3 -std=c++17 -fno-fast-math -ffp-contract=off -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
