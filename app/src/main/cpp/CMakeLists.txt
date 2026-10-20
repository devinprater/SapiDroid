cmake_minimum_required(VERSION 3.22.1)
project(sapidroid C)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine)

set(ENGINE_SOURCES
    ${ENGINE_DIR}/zira_tts.c
    ${ENGINE_DIR}/zf1_ana.c
    ${ENGINE_DIR}/zf1_dat.c
    ${ENGINE_DIR}/zf1_engine.c
    ${ENGINE_DIR}/zf1_frag.c
    ${ENGINE_DIR}/zf1_fst.c
    ${ENGINE_DIR}/zf1_fstpm.c
    ${ENGINE_DIR}/zf1_lex.c
    ${ENGINE_DIR}/zf1_lts.c
    ${ENGINE_DIR}/zf1_modules.c
    ${ENGINE_DIR}/zf1_morph.c
    ${ENGINE_DIR}/zf1_poly.c
    ${ENGINE_DIR}/zf1_pos.c
    ${ENGINE_DIR}/zf1_post.c
    ${ENGINE_DIR}/zf1_pron.c
    ${ENGINE_DIR}/zf1_pron_oov.c
    ${ENGINE_DIR}/zf1_ss.c
    ${ENGINE_DIR}/zf1_tn.c
    ${ENGINE_DIR}/zf1_util.c
    ${ENGINE_DIR}/zf1_wb.c
    ${ENGINE_DIR}/zf2_feat.c
    ${ENGINE_DIR}/zf2_main.c
    ${ENGINE_DIR}/zf2_prosody.c
    ${ENGINE_DIR}/zf2_tree.c
    ${ENGINE_DIR}/zf2_units.c
    ${ENGINE_DIR}/zb_apm.c
    ${ENGINE_DIR}/zb_dur.c
    ${ENGINE_DIR}/zb_mlpg.c
    ${ENGINE_DIR}/zb_vocoder.c
    ${ENGINE_DIR}/zb_vocoder_int.c
    ${ENGINE_DIR}/zb_synth.c
    ${ENGINE_DIR}/zb_synth_int.c
    ${ENGINE_DIR}/zb_io.c
    ${ENGINE_DIR}/zb_sonic.c
    ${ENGINE_DIR}/zb_wave.c
    ${ENGINE_DIR}/zb_ratechg.c
)

set(SAPI4_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sapi4)
file(GLOB SAPI4_SOURCES ${SAPI4_DIR}/src/*.c)
list(APPEND SAPI4_SOURCES
    ${SAPI4_DIR}/lib/sapi4tts.c
    ${SAPI4_DIR}/lib/stubs.c
    ${SAPI4_DIR}/port/dllimage.c
    ${CMAKE_CURRENT_SOURCE_DIR}/sapidroid_sapi4_jni.c
)

add_library(sapidroid SHARED sapidroid_jni.c ${ENGINE_SOURCES} ${SAPI4_SOURCES})

target_include_directories(sapidroid PRIVATE ${ENGINE_DIR} ${SAPI4_DIR}/lib ${SAPI4_DIR}/src ${SAPI4_DIR}/port)

# The engine is bit-exact only without floating point contraction and fast math.
target_compile_options(sapidroid PRIVATE
    -std=c99
    -O2
    -ffp-contract=off
    -fno-fast-math
    -fno-finite-math-only
    -Wno-unused-parameter
)

target_link_libraries(sapidroid log m)
