@file:Suppress("FunctionName", "unused")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg.wasm

import kotlin.js.JsAny

/*
 * GENERATED from native/kitecodec-c/signature-baseline.txt. Do not edit.
 *
 * One external per exported C entry point. Each takes the emscripten module as its
 * first argument, because the codec lives in a SEPARATE wasm module with its own linear
 * memory: Kotlin/Wasm cannot link to it directly and calls across through JS.
 * Pointers are Int, which is what a wasm32 address is, and stay opaque on this side.
 *
 * INTERNAL. This is the module's own wiring, not its API: a consumer calls MediaSource
 * and Frame, never these. Publishing 196 generated externals would commit the library
 * to a surface that exists only because the codec lives in another wasm module.
 */

@JsFun("(m) => m._kc_ffmpeg_configuration()")
internal external fun kc_ffmpeg_configuration(module: JsAny): Int

@JsFun("(m, a0) => m._kc_ffmpeg_library_name(a0)")
internal external fun kc_ffmpeg_library_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._kc_verdict_name(a0)")
internal external fun kc_verdict_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codec_id_name(a0)")
internal external fun ffkmp_codec_id_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_dict_entry_key(a0)")
internal external fun ffkmp_dict_entry_key(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_dict_entry_value(a0)")
internal external fun ffkmp_dict_entry_value(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_iformat_name(a0)")
internal external fun ffkmp_fmt_iformat_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_pix_fmt_name(a0)")
internal external fun ffkmp_pix_fmt_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_sample_fmt_name(a0)")
internal external fun ffkmp_sample_fmt_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_strerror(a0)")
internal external fun ffkmp_strerror(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_find_decoder_by_id(a0)")
internal external fun ffkmp_find_decoder_by_id(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_find_decoder_by_name(a0)")
internal external fun ffkmp_find_decoder_by_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_find_encoder_by_name(a0)")
internal external fun ffkmp_find_encoder_by_name(module: JsAny, a0: Int): Int

@JsFun("(m) => m._ffkmp_averror_eagain()")
internal external fun ffkmp_averror_eagain(module: JsAny): Int

@JsFun("(m) => m._ffkmp_averror_eof()")
internal external fun ffkmp_averror_eof(module: JsAny): Int

@JsFun("(m) => m._ffkmp_avseek_flag_any()")
internal external fun ffkmp_avseek_flag_any(module: JsAny): Int

@JsFun("(m) => m._ffkmp_avseek_flag_backward()")
internal external fun ffkmp_avseek_flag_backward(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_codec_first_pix_fmt(a0)")
internal external fun ffkmp_codec_first_pix_fmt(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codec_first_sample_fmt(a0)")
internal external fun ffkmp_codec_first_sample_fmt(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codec_id(a0)")
internal external fun ffkmp_codec_id(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codec_supports_pix_fmt(a0, a1)")
internal external fun ffkmp_codec_supports_pix_fmt(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_channels(a0)")
internal external fun ffkmp_codecctx_channels(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_frame_size(a0)")
internal external fun ffkmp_codecctx_frame_size(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_from_par(a0, a1)")
internal external fun ffkmp_codecctx_from_par(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_height(a0)")
internal external fun ffkmp_codecctx_height(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_open(a0, a1)")
internal external fun ffkmp_codecctx_open(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_pix_fmt(a0)")
internal external fun ffkmp_codecctx_pix_fmt(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_receive_frame(a0, a1)")
internal external fun ffkmp_codecctx_receive_frame(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_receive_packet(a0, a1)")
internal external fun ffkmp_codecctx_receive_packet(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_sample_rate(a0)")
internal external fun ffkmp_codecctx_sample_rate(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_send_frame(a0, a1)")
internal external fun ffkmp_codecctx_send_frame(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_send_packet(a0, a1)")
internal external fun ffkmp_codecctx_send_packet(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_codecctx_set_opt(a0, a1, a2)")
internal external fun ffkmp_codecctx_set_opt(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_use_videotoolbox(a0)")
internal external fun ffkmp_codecctx_use_videotoolbox(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecctx_width(a0)")
internal external fun ffkmp_codecctx_width(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_bit_depth(a0)")
internal external fun ffkmp_codecpar_bit_depth(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_channels(a0)")
internal external fun ffkmp_codecpar_channels(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_chroma_location(a0)")
internal external fun ffkmp_codecpar_chroma_location(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_chroma_subsampling(a0)")
internal external fun ffkmp_codecpar_chroma_subsampling(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_codec_id(a0)")
internal external fun ffkmp_codecpar_codec_id(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_codec_type(a0)")
internal external fun ffkmp_codecpar_codec_type(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_color_primaries(a0)")
internal external fun ffkmp_codecpar_color_primaries(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_color_range(a0)")
internal external fun ffkmp_codecpar_color_range(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_color_space(a0)")
internal external fun ffkmp_codecpar_color_space(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_color_transfer(a0)")
internal external fun ffkmp_codecpar_color_transfer(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecpar_copy_for_mux(a0, a1)")
internal external fun ffkmp_codecpar_copy_for_mux(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_codecpar_extradata(a0, a1, a2)")
internal external fun ffkmp_codecpar_extradata(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_format(a0)")
internal external fun ffkmp_codecpar_format(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_codecpar_from_context(a0, a1)")
internal external fun ffkmp_codecpar_from_context(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_height(a0)")
internal external fun ffkmp_codecpar_height(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_level(a0)")
internal external fun ffkmp_codecpar_level(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_profile(a0)")
internal external fun ffkmp_codecpar_profile(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_sample_rate(a0)")
internal external fun ffkmp_codecpar_sample_rate(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_width(a0)")
internal external fun ffkmp_codecpar_width(module: JsAny, a0: Int): Int

@JsFun("(m) => m._ffkmp_disposition_attached_pic()")
internal external fun ffkmp_disposition_attached_pic(module: JsAny): Int

@JsFun("(m) => m._ffkmp_disposition_default()")
internal external fun ffkmp_disposition_default(module: JsAny): Int

@JsFun("(m) => m._ffkmp_disposition_forced()")
internal external fun ffkmp_disposition_forced(module: JsAny): Int

@JsFun("(m) => m._ffkmp_disposition_hearing_impaired()")
internal external fun ffkmp_disposition_hearing_impaired(module: JsAny): Int

@JsFun("(m) => m._ffkmp_disposition_visual_impaired()")
internal external fun ffkmp_disposition_visual_impaired(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_filter_exists(a0)")
internal external fun ffkmp_filter_exists(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_fmt_alloc_output2(a0, a1, a2)")
internal external fun ffkmp_fmt_alloc_output2(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_chapter_count(a0)")
internal external fun ffkmp_fmt_chapter_count(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4) => m._ffkmp_fmt_chapter_get(a0, a1, a2, a3, a4)")
internal external fun ffkmp_fmt_chapter_get(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_find_stream_info(a0)")
internal external fun ffkmp_fmt_find_stream_info(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_io_open(a0, a1)")
internal external fun ffkmp_fmt_io_open(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_is_seekable(a0)")
internal external fun ffkmp_fmt_is_seekable(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_open_input(a0, a1)")
internal external fun ffkmp_fmt_open_input(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5) => m._ffkmp_fmt_open_input2(a0, a1, a2, a3, a4, a5)")
internal external fun ffkmp_fmt_open_input2(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_read_frame(a0, a1)")
internal external fun ffkmp_fmt_read_frame(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5) => m._ffkmp_fmt_seek_file(a0, a1, a2, a3, a4, a5)")
internal external fun ffkmp_fmt_seek_file(module: JsAny, a0: Int, a1: Int, a2: Long, a3: Long, a4: Long, a5: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_fmt_seek_micros(a0, a1, a2)")
internal external fun ffkmp_fmt_seek_micros(module: JsAny, a0: Int, a1: Int, a2: Long): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_fmt_set_metadata(a0, a1, a2)")
internal external fun ffkmp_fmt_set_metadata(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_fmt_set_opt(a0, a1, a2)")
internal external fun ffkmp_fmt_set_opt(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_write_frame(a0, a1)")
internal external fun ffkmp_fmt_write_frame(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_write_header(a0)")
internal external fun ffkmp_fmt_write_header(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_write_trailer(a0)")
internal external fun ffkmp_fmt_write_trailer(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_channels(a0)")
internal external fun ffkmp_frame_channels(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_chroma_location(a0)")
internal external fun ffkmp_frame_chroma_location(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_color_primaries(a0)")
internal external fun ffkmp_frame_color_primaries(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_color_range(a0)")
internal external fun ffkmp_frame_color_range(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_color_trc(a0)")
internal external fun ffkmp_frame_color_trc(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_colorspace(a0)")
internal external fun ffkmp_frame_colorspace(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_frame_copy_to_buffer(a0, a1, a2)")
internal external fun ffkmp_frame_copy_to_buffer(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_frame_fill_audio(a0, a1, a2)")
internal external fun ffkmp_frame_fill_audio(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_frame_fill_video(a0, a1, a2)")
internal external fun ffkmp_frame_fill_video(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_format(a0)")
internal external fun ffkmp_frame_format(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_get_buffer(a0, a1)")
internal external fun ffkmp_frame_get_buffer(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_height(a0)")
internal external fun ffkmp_frame_height(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_hw_download(a0, a1)")
internal external fun ffkmp_frame_hw_download(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_is_hardware(a0)")
internal external fun ffkmp_frame_is_hardware(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_is_keyframe(a0)")
internal external fun ffkmp_frame_is_keyframe(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_linesize(a0, a1)")
internal external fun ffkmp_frame_linesize(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_nb_samples(a0)")
internal external fun ffkmp_frame_nb_samples(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_plane_count(a0)")
internal external fun ffkmp_frame_plane_count(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_plane_height(a0, a1)")
internal external fun ffkmp_frame_plane_height(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_sample_rate(a0)")
internal external fun ffkmp_frame_sample_rate(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_width(a0)")
internal external fun ffkmp_frame_width(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) => m._ffkmp_graph_build_audio(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11)")
internal external fun ffkmp_graph_build_audio(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) => m._ffkmp_graph_build_audio_multi(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)")
internal external fun ffkmp_graph_build_audio_multi(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) => m._ffkmp_graph_build_video(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)")
internal external fun ffkmp_graph_build_video(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int): Int

@JsFun("(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13) => m._ffkmp_graph_build_video_multi(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13)")
internal external fun ffkmp_graph_build_video_multi(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_graph_receive(a0, a1)")
internal external fun ffkmp_graph_receive(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_graph_send(a0, a1)")
internal external fun ffkmp_graph_send(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1, a2, a3) => m._ffkmp_image_get_buffer_size(a0, a1, a2, a3)")
internal external fun ffkmp_image_get_buffer_size(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int): Int

@JsFun("(m) => m._ffkmp_media_type_attachment()")
internal external fun ffkmp_media_type_attachment(module: JsAny): Int

@JsFun("(m) => m._ffkmp_media_type_audio()")
internal external fun ffkmp_media_type_audio(module: JsAny): Int

@JsFun("(m) => m._ffkmp_media_type_data()")
internal external fun ffkmp_media_type_data(module: JsAny): Int

@JsFun("(m) => m._ffkmp_media_type_subtitle()")
internal external fun ffkmp_media_type_subtitle(module: JsAny): Int

@JsFun("(m) => m._ffkmp_media_type_video()")
internal external fun ffkmp_media_type_video(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_oformat_global_header(a0)")
internal external fun ffkmp_oformat_global_header(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_packet_is_keyframe(a0)")
internal external fun ffkmp_packet_is_keyframe(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_packet_size(a0)")
internal external fun ffkmp_packet_size(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_packet_stream_index(a0)")
internal external fun ffkmp_packet_stream_index(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_pix_fmt_from_name(a0)")
internal external fun ffkmp_pix_fmt_from_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_sample_fmt_from_name(a0)")
internal external fun ffkmp_sample_fmt_from_name(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1, a2) => m._ffkmp_samples_copy_to_buffer(a0, a1, a2)")
internal external fun ffkmp_samples_copy_to_buffer(module: JsAny, a0: Int, a1: Int, a2: Int): Int

@JsFun("(m, a0) => m._ffkmp_samples_get_buffer_size(a0)")
internal external fun ffkmp_samples_get_buffer_size(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_stream_disposition(a0)")
internal external fun ffkmp_stream_disposition(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_stream_index(a0)")
internal external fun ffkmp_stream_index(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_stream_rotation_degrees(a0)")
internal external fun ffkmp_stream_rotation_degrees(module: JsAny, a0: Int): Int

@JsFun("(m) => m._kc_init()")
internal external fun kc_init(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_codecpar_bit_rate(a0)")
internal external fun ffkmp_codecpar_bit_rate(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_codecpar_ch_layout_mask(a0)")
internal external fun ffkmp_codecpar_ch_layout_mask(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_fmt_duration(a0)")
internal external fun ffkmp_fmt_duration(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_fmt_start_time(a0)")
internal external fun ffkmp_fmt_start_time(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_frame_ch_layout_mask(a0)")
internal external fun ffkmp_frame_ch_layout_mask(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_frame_duration(a0)")
internal external fun ffkmp_frame_duration(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_frame_pts(a0)")
internal external fun ffkmp_frame_pts(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_packet_dts(a0)")
internal external fun ffkmp_packet_dts(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_packet_duration(a0)")
internal external fun ffkmp_packet_duration(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_packet_pos(a0)")
internal external fun ffkmp_packet_pos(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_packet_pts(a0)")
internal external fun ffkmp_packet_pts(module: JsAny, a0: Int): Long

@JsFun("(m, a0, a1, a2, a3, a4) => m._ffkmp_rescale_q(a0, a1, a2, a3, a4)")
internal external fun ffkmp_rescale_q(module: JsAny, a0: Long, a1: Int, a2: Int, a3: Int, a4: Int): Long

@JsFun("(m, a0) => m._ffkmp_stream_duration_micros(a0)")
internal external fun ffkmp_stream_duration_micros(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_stream_start_time(a0)")
internal external fun ffkmp_stream_start_time(module: JsAny, a0: Int): Long

@JsFun("(m, a0) => m._ffkmp_codecctx_alloc(a0)")
internal external fun ffkmp_codecctx_alloc(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_stream_codecpar(a0)")
internal external fun ffkmp_stream_codecpar(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_chapter_metadata(a0, a1)")
internal external fun ffkmp_fmt_chapter_metadata(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_metadata(a0)")
internal external fun ffkmp_fmt_metadata(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_stream_metadata(a0)")
internal external fun ffkmp_stream_metadata(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_dict_get(a0, a1)")
internal external fun ffkmp_dict_get(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m) => m._ffkmp_frame_alloc()")
internal external fun ffkmp_frame_alloc(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_frame_clone(a0)")
internal external fun ffkmp_frame_clone(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_convert_pixfmt(a0, a1)")
internal external fun ffkmp_frame_convert_pixfmt(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m) => m._ffkmp_packet_alloc()")
internal external fun ffkmp_packet_alloc(module: JsAny): Int

@JsFun("(m, a0) => m._ffkmp_packet_clone(a0)")
internal external fun ffkmp_packet_clone(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_new_stream(a0, a1)")
internal external fun ffkmp_fmt_new_stream(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_fmt_stream(a0, a1)")
internal external fun ffkmp_fmt_stream(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m) => m._kc_abi_version()")
internal external fun kc_abi_version(module: JsAny): Int

@JsFun("(m, a0, a1) => m._ffkmp_frame_plane(a0, a1)")
internal external fun ffkmp_frame_plane(module: JsAny, a0: Int, a1: Int): Int

@JsFun("(m, a0) => m._ffkmp_packet_data(a0)")
internal external fun ffkmp_packet_data(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_nb_streams(a0)")
internal external fun ffkmp_fmt_nb_streams(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_fmt_io_opaque(a0)")
internal external fun ffkmp_fmt_io_opaque(module: JsAny, a0: Int): Int

@JsFun("(m, a0, a1) => m._ffkmp_buffersink_set_frame_size(a0, a1)")
internal external fun ffkmp_buffersink_set_frame_size(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_buffersink_time_base(a0, a1, a2)")
internal external fun ffkmp_buffersink_time_base(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0) => m._ffkmp_codecctx_flush(a0)")
internal external fun ffkmp_codecctx_flush(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_codecctx_free(a0)")
internal external fun ffkmp_codecctx_free(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1, a2, a3, a4) => m._ffkmp_codecctx_set_audio(a0, a1, a2, a3, a4)")
internal external fun ffkmp_codecctx_set_audio(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Long): Unit

@JsFun("(m, a0) => m._ffkmp_codecctx_set_full_range(a0)")
internal external fun ffkmp_codecctx_set_full_range(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_codecctx_set_global_header(a0)")
internal external fun ffkmp_codecctx_set_global_header(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_codecctx_set_low_delay(a0, a1)")
internal external fun ffkmp_codecctx_set_low_delay(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_codecctx_set_threads(a0, a1, a2)")
internal external fun ffkmp_codecctx_set_threads(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) => m._ffkmp_codecctx_set_video(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9)")
internal external fun ffkmp_codecctx_set_video(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Long, a9: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_codecctx_time_base(a0, a1, a2)")
internal external fun ffkmp_codecctx_time_base(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_codecpar_sample_aspect_ratio(a0, a1, a2)")
internal external fun ffkmp_codecpar_sample_aspect_ratio(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0) => m._ffkmp_dict_free(a0)")
internal external fun ffkmp_dict_free(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_fmt_avoid_negative_ts(a0)")
internal external fun ffkmp_fmt_avoid_negative_ts(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_fmt_close_input(a0)")
internal external fun ffkmp_fmt_close_input(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_fmt_close_input_io(a0)")
internal external fun ffkmp_fmt_close_input_io(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_fmt_interrupt(a0)")
internal external fun ffkmp_fmt_interrupt(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_fmt_free_output(a0)")
internal external fun ffkmp_fmt_free_output(module: JsAny, a0: Int): Int

@JsFun("(m, a0) => m._ffkmp_frame_free(a0)")
internal external fun ffkmp_frame_free(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_frame_sample_aspect_ratio(a0, a1, a2)")
internal external fun ffkmp_frame_sample_aspect_ratio(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_ch_layout_default(a0, a1)")
internal external fun ffkmp_frame_set_ch_layout_default(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_format(a0, a1)")
internal external fun ffkmp_frame_set_format(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_height(a0, a1)")
internal external fun ffkmp_frame_set_height(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_nb_samples(a0, a1)")
internal external fun ffkmp_frame_set_nb_samples(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_pts(a0, a1)")
internal external fun ffkmp_frame_set_pts(module: JsAny, a0: Int, a1: Long): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_sample_rate(a0, a1)")
internal external fun ffkmp_frame_set_sample_rate(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_frame_set_width(a0, a1)")
internal external fun ffkmp_frame_set_width(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0) => m._ffkmp_frame_unref(a0)")
internal external fun ffkmp_frame_unref(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_frame_use_best_effort_ts(a0)")
internal external fun ffkmp_frame_use_best_effort_ts(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_graph_free(a0)")
internal external fun ffkmp_graph_free(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_packet_free(a0)")
internal external fun ffkmp_packet_free(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_packet_move_ref(a0, a1)")
internal external fun ffkmp_packet_move_ref(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0, a1, a2, a3, a4) => m._ffkmp_packet_rescale_ts(a0, a1, a2, a3, a4)")
internal external fun ffkmp_packet_rescale_ts(module: JsAny, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int): Unit

@JsFun("(m, a0, a1) => m._ffkmp_packet_set_dts(a0, a1)")
internal external fun ffkmp_packet_set_dts(module: JsAny, a0: Int, a1: Long): Unit

@JsFun("(m, a0, a1) => m._ffkmp_packet_set_pts(a0, a1)")
internal external fun ffkmp_packet_set_pts(module: JsAny, a0: Int, a1: Long): Unit

@JsFun("(m, a0, a1) => m._ffkmp_packet_set_stream_index(a0, a1)")
internal external fun ffkmp_packet_set_stream_index(module: JsAny, a0: Int, a1: Int): Unit

@JsFun("(m, a0) => m._ffkmp_packet_unref(a0)")
internal external fun ffkmp_packet_unref(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_stream_avg_frame_rate(a0, a1, a2)")
internal external fun ffkmp_stream_avg_frame_rate(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0) => m._ffkmp_stream_discard_all(a0)")
internal external fun ffkmp_stream_discard_all(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_stream_discard_none(a0)")
internal external fun ffkmp_stream_discard_none(module: JsAny, a0: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_stream_set_time_base(a0, a1, a2)")
internal external fun ffkmp_stream_set_time_base(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0, a1, a2) => m._ffkmp_stream_time_base(a0, a1, a2)")
internal external fun ffkmp_stream_time_base(module: JsAny, a0: Int, a1: Int, a2: Int): Unit

@JsFun("(m, a0) => m._kc_ffmpeg_report_get(a0)")
internal external fun kc_ffmpeg_report_get(module: JsAny, a0: Int): Unit

@JsFun("(m, a0) => m._ffkmp_frame_hw_surface(a0)")
internal external fun ffkmp_frame_hw_surface(module: JsAny, a0: Int): Int
