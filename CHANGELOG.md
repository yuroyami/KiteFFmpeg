# Changelog

All notable changes to KiteFFmpeg are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KiteFFmpeg is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

## [Unreleased]

### Upgrading

- `VideoStreamInfo` gains `dolbyVision`, `crop`, `spherical` and `stereo3d`, which changes its
  constructor and `copy`. Recompile a library built against 0.4.0.
- `StreamInfo.language` of a Matroska track that carries `LanguageBCP47` is that tag now (#150).
  MKVToolNix writes it on every track, so an English track from such a file reads `en` where it
  read `eng`, and a Traditional Chinese one `zh-Hant` where it read `chi`. Code that compares a
  language with a three-letter string has to accept the tag too. `TrackSelector` compares
  languages rather than strings now (#158), so a preference for `eng` still finds such a track.
- `StreamInfo.language` of an MP4 or MOV track that carries an `elng` box is that tag now (#157),
  so a track that read `zho` can read `zh-Hant`. A remux into MP4 or MOV of a stream whose tag says
  more than its code, such as `pt-BR`, now reads back as that tag rather than as `por`.
- `MediaProbe` gains `programs`, which changes its constructor and `copy`. Recompile a library
  built against 0.4.0.
- `MediaSource.primaryAudio` of a source with programmes is now the sound of `primaryVideo`'s own
  programme (#165), so it can name another stream than before, and it is null when that
  programme has no sound and no stream sits outside every programme.
- `MediaSource.streams` and `MediaSource.programs` can change after the open (#151): a live
  transport stream adds streams and moves them between programmes as it plays, and an entry read
  before its stream had a packet is replaced once it has one. Code that kept the list from the open
  and finds a stream by comparing whole entries should find it by index, and read the lists again,
  or follow `Packet.newStreams` and `Packet.newPrograms`, to see what was added.
- The message of an `FFmpegException` thrown by an open, by adding an encoder or a copied stream,
  by writing the header or by building a filter graph ends with the lines FFmpeg logged for it now
  (#170), and so does its `error`'s message. Code that compares a whole message with a fixed string
  should compare `error`'s type and `code` instead, or look for the words it wants inside it.
- The C ABI is 5.0 (#167, #168). `ffkmp_fmt_open_input_io2` takes a `tags_fn` after its `seek_fn`
  and the input's `location` after its `url`, and `kc_io_opener` gains `location_fn` after
  `close_fn`, so C code that calls the helper layer itself, or fills a `kc_io_opener`, has to be
  built again against the new header. The Kotlin API changes only by what the entries below add.

### Added

- A failed open carries what FFmpeg logged for it (#170). FFmpeg says why it refused a file only in
  its log, so an exception used to say "Invalid data found when processing input" where FFmpeg had
  logged "moov atom not found", and a caller with no log sink had no way to learn more.
  `FFmpegException.logged` now holds the lines FFmpeg logged at error level or worse, on the
  calling thread, while the call that failed ran: opening a `MediaSource` with its stream
  discovery, opening a decoder, a subtitle decoder or a subtitle converter, opening a `MediaSink`,
  adding an encoder or a copied stream, writing the header, and building a `FilterGraph`. The first
  eight are kept, and the exception's message and its `error`'s message end with them, as
  `FFmpeg logged: [mov,mp4,m4a,3gp,3g2,mj2] moov atom not found`. The lines are kept whether or not
  a log sink is installed and at whatever level it listens, and a sink still hears each one. Reads,
  decodes and writes are not covered, because a long run logs unrelated warnings that would only
  mislead. The C ABI is 5.2 and adds `ffkmp_log_capture_begin` and `ffkmp_log_capture_end` with
  their accessors and `ffkmp_log_capture_free`, a capture of the calling thread's error lines that
  needs no sink and nests.

- A stream FFmpeg adds after the open reaches a caller (#151). A live transport stream can start its
  sound after its picture, add subtitles at a programme boundary or move a channel's sound to a new
  stream, and FFmpeg adds such a stream while it reads, where `MediaSource.streams` used to stay as
  the open found it and every reader dropped the new stream's packets. `MediaSource.streams` grows
  now, in index order and never shorter, and `MediaSource.programs` follows the programme tables, as
  of the last packet read, by a packet reader or a decode flow. The first packet a reader hands out
  after a change carries the whole new list as `Packet.newStreams` or `Packet.newPrograms`, and a
  change no packet carried yet rides the next reader's first packet. The packets of a new stream
  that FFmpeg handed out before that packet are held, up to 16 MB, and the read after a `reselect`
  that adds the stream hands them out first, so it starts from its first packet; a read or a seek
  without it drops them. An entry read before FFmpeg had any packet of its stream, such as a late
  MP2 sound the programme table names MP3 with no channels, is read again at the stream's first
  packet, and the entry it replaced still names the stream to a reader or a decoder. A packet takes
  its time base from its stream's entry as it stands. The web build's `openDecoder` now refuses an
  entry from another source, as the other backends' did. The C ABI is 5.1 and adds
  `ffkmp_fmt_layout_stamp`, a number that moves when the stream count or the programme table does.

- A `MediaByteSource` hands FFmpeg the tags its bytes bring, through `takeTags` (#168). A player
  that reads an internet radio station through its own HTTP client takes the title blocks out of
  the audio, as FFmpeg's own `http` does, and hands each new title over. `takeTags` is asked after
  every read that brought bytes, only of the source given to `MediaSource.open`, and null, the
  default, hands over nothing. The tags belong at the first byte of that read and merge into the
  container's tags, so they reach a caller as `Packet.newContainerTags` on the first packet FFmpeg
  read past that byte and in `MediaSource.metadata` from then on, and the tags the open's own reads
  brought are in `metadata` when the open returns. A key or a value ends at its first NUL, a pair
  with an empty key is left out, an unpaired surrogate becomes U+FFFD, and an exception thrown from
  `takeTags` fails the read it followed with that exception as the cause. On the web a source read
  on demand is asked as it is read, and a staged one as it drains, with each answer reaching FFmpeg
  when it reads the byte the answer belongs at. The C ABI is 5.0 and adds `ffkmp_io_tag`.

- Tags that change during playback reach a caller (#135). `Packet.newContainerTags` and
  `Packet.newStreamTags` are each the whole new tag set, on the first packet a read hands out after
  FFmpeg applied it and null on every other packet, so a player can show a new title when that
  packet plays rather than when its demuxer read ahead. The container's tags change with a
  station's ICY title through FFmpeg's own `http`, an ID3 tag between ADTS frames or an FLV
  `onMetaData`, and a stream's with the next song of a chained Ogg, which replaces its comments, or
  a timed ID3 packet of an MPEG-TS or HLS data stream, which adds to them. A stream's change rides
  that stream's next packet, so a reader that did not select it never sees it. `MediaSource.metadata`
  is the container's tags as of the last packet read on every backend now, updated by the decode
  flows too, where the web read FFmpeg's live dictionary and the other backends kept the open's copy
  for ever. `StreamInfo.metadata` keeps what the stream said at open. The C ABI is 4.2 and adds
  `ffkmp_fmt_take_tag_changes`.

- `MediaSource.pause` and `MediaSource.resume` tell a live source's server that playback paused or
  resumed (#136), through FFmpeg's `av_read_pause` and `av_read_play` on every backend. An `rtsp://`
  stream sends its server PAUSE and PLAY, so the camera stops sending while a viewer is paused, and
  an `rtmp://` feed sends RTMP's pause and unpause. Every other source, a file and a
  `MediaByteSource` included, answers false and nothing changes. A server ends a session that hears
  nothing for its timeout, sixty seconds unless it says otherwise and a few seconds on some
  cameras, and FFmpeg sends its keepalive only from inside a read, so a session paused for longer
  ended and the resume met 454 Session Not Found. While paused, calling `pause` again every second
  or so now sends that keepalive, GET_PARAMETER or OPTIONS as the server supports, once half the
  session's timeout has passed since the last request, and nothing in between. That half is the
  FFmpeg patch `0012-rtsp-keep-a-paused-session-alive.patch`, so it reaches a platform only with an
  FFmpeg tree built from it, and the trees of 0.4.0 do not carry it. A `resume` reaches FFmpeg only
  while a pause is in effect, because a PLAY on a playing RTSP stream restarts it from the last
  seek, and a refused one throws and leaves the source paused. The C ABI is 4.1 and adds
  `ffkmp_fmt_read_pause` and `ffkmp_fmt_read_play`.

- `MediaByteSource.location` says where a source's bytes came from when that is not the address
  they were asked for, as after an HTTP redirect (#167). FFmpeg's HLS reader resolves the addresses
  inside a playlist against it, as it does after a redirect its own `http` follows, for the source
  handed to `MediaSource.open` and for every source a nested opener returns, so a playlist that a
  CDN moved to another host asks for its segments, keys and variants there rather than beside the
  address it was asked for. It is read once, as the source opens, on every backend including the
  web, and a getter that throws fails that address with its exception as the cause. The C ABI is
  4.0 and gives `ffkmp_fmt_open_input_io2` a `location` and `kc_io_opener` a `location_fn`.

- `MediaSource.programs` and `MediaProbe.programs` list a source's programmes (#148), the sets of
  streams that play together, such as the channels of a DVB or IPTV transport stream. Each
  `Program` carries FFmpeg's id, the programme number the container states, which in a transport
  stream is the service id, the indexes of its streams, and its tags, with the channel's name and
  provider as `serviceName` and `serviceProvider`. An HLS master playlist reads one programme per
  variant and a DASH presentation one holding every stream, both with no number, and a container
  with no programme tables reads none. The C ABI is 3.27 and adds `ffkmp_fmt_program_count`,
  `ffkmp_fmt_program_get`, `ffkmp_fmt_program_stream` and `ffkmp_fmt_program_metadata`.

- `VideoStreamInfo.spherical` and `VideoStreamInfo.stereo3d` say how a stream's pictures wrap
  around the viewer and how they hold the views of two eyes (#139), as Google's spherical video and
  stereoscopic boxes in MP4, Apple's video extension box in MP4 and MOV, which FFmpeg reads from
  7.1 on, and a Matroska track's `Projection` and `StereoMode` state them. FFmpeg reads all of it
  and applies none. The projection is a sealed class whose tile carries its four bounds and whose
  cube map carries its padding, the turn is the yaw, pitch and roll exactly as FFmpeg holds them,
  and the stereo layout carries the packing, whether the eyes are reversed, and Apple's primary
  eye, baseline, disparity adjustment and field of view. The C ABI is 3.26 and adds
  `ffkmp_codecpar_spherical` and `ffkmp_codecpar_stereo3d`.
- `Frame.dolbyVisionRpu()` reads the whole Dolby Vision RPU of a frame as FFmpeg's decoder parsed
  it (#138): the header, each component's reshaping curve with its polynomial and MMR pieces, the
  inverse quantization of an enhancement layer's residual, and the colour matrices and signal
  levels, every one the same number `ffprobe -show_frames` prints. A caller that composes the
  picture somewhere other than the CPU composer, such as in a shader, has what it needs, and
  `DolbyVisionRpu.coefficientValue` turns a fixed-point coefficient into a number. The inverse
  quantization's two pivots need FFmpeg 7.1 and read null before it. The C ABI is 3.25 and adds
  `ffkmp_frame_dovi_rpu`.
- `StreamDecoder.setSkipFrame` changes which frames an open video decoder skips, from the next
  packet sent (#140). It is FFmpeg's `skip_frame`, which `DecoderOptions.skipFrame` could set only
  when the decoder opened, so a caller can skip the frames nothing predicts from on the way to a
  precise seek target and go back to `DecoderSkip.None` with no flush. An H.264 decode lowered from
  `NonReference` goes on exactly as one that never skipped. A stream that is not video is refused,
  because only video decoders read the setting.
- `VideoStreamInfo.crop` holds the crop a container states for a stream's pictures (#147): a
  Matroska track's `PixelCrop` elements or an MP4 track's clean aperture, as the rows and columns
  to leave out at each edge, which FFmpeg reads from 7.1 on and does not apply. A renderer leaves
  them out; the crop the bitstream carries is still the decoder's. The C ABI is 3.21 and adds
  `ffkmp_codecpar_frame_cropping`.
- `MediaSource.durationOrigin` says where `durationMicros` came from (#134): the streams'
  timestamps, a length the container or a stream declares, or an estimate from the bit rate of the
  first frames, which FFmpeg makes for an input that states no length, such as ADTS AAC or an MP3
  without its Xing header, and which variable bit rate audio puts minutes out. A caller can then
  treat an estimated length as a hint. The C ABI is 3.20 and adds `ffkmp_fmt_duration_origin`.
- Dolby Vision (#137). `VideoStreamInfo.dolbyVision` holds the configuration record a stream
  declares, and its `baseLayerPlaysAlone` says whether the base layer is a picture of its own.
  `Frame.dolbyVision()` reads the RPU FFmpeg's decoder attached to the frame: the source's range,
  and the scene brightness of level 1 when the stream carries it. `Frame.composeDolbyVision()`
  turns a base layer and its RPU into an ordinary HDR10 frame, 10-bit 4:2:0 in BT.2020 with the
  PQ curve and the source's range as its mastering display, so a profile 5 picture, which shows
  green and purple as it is, plays on every renderer that shows HDR10. The composition runs on the
  CPU, about 70 ms for a 1080p frame on one core, and `beginDolbyVisionComposition()` splits it
  into bands of rows that may run on several threads at once. Every sample of the test clip lands
  within 4 codes of libplacebo's composition, and within one code of the same arithmetic done in
  double precision. The C ABI is 3.18 and adds `ffkmp_codecpar_dovi_config`,
  `ffkmp_frame_dovi_metadata`, `ffkmp_frame_dovi_compose_prepare` and
  `ffkmp_frame_dovi_compose_rows`. The `web` zip of 0.4.0 carries none of them, so the web needs a
  codec module linked from this release.

- Live network streams open through `MediaSource.open`: `udp://` and `rtp://`, an SDP file that
  describes an RTP session, `rtsp://` over UDP or TCP with `rtsp_transport` among the open options,
  and `rtmp://` (#124). The recipe gains the `udp`, `rtp` and `rtmp` protocols, and with them the
  `rtsp`, `sdp`, `rtp` and `sap` demuxers, which a named disable of `udp` and `rtp` used to drop.
  None needs a library. `rtmps` and SRT stay out, and the web build has none of them. An SDP file
  read from disk needs `DemuxOptions(protocolWhitelist = setOf("file", "udp", "rtp"))`, because
  FFmpeg lets a file reach only `file`, `crypto` and `data`. Measured on Linux x64, the recipe adds
  0.44 MB to `libavformat.a` and 0.25 MB to the JVM's linked library, which grows from 25.05 MB to
  25.29 MB. The trees of 0.4.0 do not carry any of it.
- The web module's FFmpeg reads HLS playlists. Its recipe gains the `hls` demuxer, and FFmpeg's
  configure brings the segment readers `mpegts`, `aac`, `ac3` and `eac3` along with it. The web
  build still carries no network protocol, so every playlist, segment and key has to come through a
  nested opener (#123). Built with emscripten 6.0.11, `kite.wasm` grows by 81,846 bytes, from
  4,576,446 to 4,658,292, which is 37,726 bytes after gzip. The `web` zip of 0.4.0 does not carry
  it.
- On the web, `MediaSource.open` takes a `nestedOpener`, so an HLS playlist plays in a browser, and
  DASH does too as the HLS playlists KitePlayer writes for it (#123). A read on the web cannot wait,
  so each source the opener returns is read whole into the codec module's memory and closed
  straight away, and the opener has to answer at once: from bytes the page already holds, or from a
  synchronous request inside a Web Worker. A module linked from an FFmpeg without the
  `trust_io_open` patch refuses the opener with `FFmpegError.Unsupported`, as the other backends
  do, and a module without the `hls` demuxer, such as the one in the `web` zip of 0.4.0, cannot
  open the playlist at all.

- On the web, a `MediaByteSource` is read on demand wherever a read may block, which is a Web
  Worker: FFmpeg calls its `read` and `seek` as it needs bytes, so a source that answers with
  synchronous range requests plays after its first few reads, memory does not grow with the file,
  and neither a size above 512 MiB nor an unknown size is refused (#133). The source stays open
  until the `MediaSource` closes, as on the other platforms, and one that cannot seek makes an input
  that cannot seek either. On a page's main thread, where nothing may block, the source is still
  read whole during the open, up to 512 MiB. The sources a nested opener returns are still read
  whole on both.

### Fixed

- `MediaSource.chapters` of a Matroska file is the default edition's chapters (#172). A file can
  hold several editions, such as a theatrical and an extended cut, and FFmpeg read the chapters of
  all of them into one list, keeping each that started later than the one before, so a second
  edition's chapters came out mixed into the first's. It also listed hidden and disabled chapters,
  and a chapter that plays another file at that file's times. The list now follows RFC 9559: the
  first edition flagged default, or the first when none is, without its hidden, disabled or linked
  chapters, and a chapter's tags still reach it in any edition. The fix is the FFmpeg patch
  `0013-matroska-take-the-chapters-of-the-default-edition.patch`, with a C suite,
  `test_editions`, and a contract test on a file written by mkvmerge.
- In the subtitle chain that KitePlayer links, the `ass-chain` release assets, a character that the
  style's font and the default family both lack is drawn from a loaded font that has it (#152). A
  chain with no system font provider, which is Android, Linux and the web, drew an empty box for
  it even when another font added from memory had it, so Chinese, Japanese, Korean, Thai or Arabic
  text in a script naming a font the device lacks came out as boxes. The fix is the libass patch
  `0001-fontselect-fall-back-to-a-loaded-font-with-the-glyph.patch`: after the provider's own
  fallback, libass takes the first loaded font with the character and the face of its family that
  best fits the style. It reaches a platform only with a chain built from it, and the
  `ass-chain-r2` assets do not carry it.
- On the web, a failed open, stream discovery, decoder open or subtitle decoder open is typed by the
  code FFmpeg returned, as on the JVM and native (#171). The open was always `InvalidData`, so a
  source that threw read as invalid data rather than `Io`, the stream discovery and the decoder
  open dropped the code altogether, and the two decoder opens answered `Internal` for a refused
  option or a memory failure. Each message now carries FFmpeg's text for the code.

- On the web, a packet read or a seek that fails because the `MediaByteSource` threw carries that
  exception as its cause, and its error is typed from FFmpeg's code, so a range request that fails
  in the middle of a song reads as an I/O error with the request's own failure behind it (#169). It
  read as an internal error with no cause, while the JVM and native explained it and the web
  explained only a failed open. `MediaSource.pause` and `resume` carry the cause too.
- An HLS playlist that defines variables with `EXT-X-DEFINE` plays (#166). FFmpeg's HLS reader knew
  no variables, so a stream that hands a token from its master playlist down to every media playlist
  and segment asked for addresses that still held `{$token}`, and every one failed. The reader now
  takes a variable from `NAME` and `VALUE`, from the master playlist by `IMPORT`, and from the query
  of the playlist's own address by `QUERYPARAM`, decoded, and replaces each reference in addresses
  and in the attributes of the variant, rendition, key and initialization section tags, once. A
  playlist that breaks the specification's rules for variables, such as one that uses a variable
  nothing defined, fails the open, and FFmpeg logs an error naming the variable. The fix is the
  FFmpeg patch `0011-hls-substitute-playlist-variables.patch`, so it reaches a platform only with an
  FFmpeg tree built from it, and the trees of 0.4.0 do not carry it.
- A build of FFmpeg, dav1d or the libass chain compiles its vendored checkout only when it holds
  the commit its tag names, with nothing changed, added or ignored in it (#145). A checkout of
  another release, or one with an edited file, used to build under the pinned tag's name, and an
  edit left a tree that was already built up to date, so an output could name a release it was
  not. Each tag is now pinned beside its full commit, and the build stops before it compiles
  anything and names whatever differs. The commit the checkout holds, with a digest of any change,
  is a task input, so a checkout that moves or changes runs the build again and is refused, while
  an untouched one stays up to date. A change to FFmpeg belongs in a patch under
  `native/patches/ffmpeg`. The web build's record writes the commit it verified rather than
  whatever the checkout's HEAD said.
- The initial view that Google's first spherical box states in an MP4 track reads as the
  mapping's yaw, pitch and roll (#161). FFmpeg's reader found the projection and the stereo mode
  in the box's XML but read all three angles as 0, because it parsed each number from the start of
  its tag rather than after it. The specification draws heading, pitch and roll on the axes FFmpeg
  draws for yaw, pitch and roll, so they carry over with their signs as they are, a fraction is
  kept to the nearest step of 16.16, and the number reads the same in every locale. The fix is the
  FFmpeg patch `0010-mov-read-the-initial-view-of-the-spherical-uuid-box.patch`, so it reaches a
  platform only with an FFmpeg tree built from it, and the trees of 0.4.0 do not carry it.
- A frame converted to another pixel format, to be saved as an image or fed to an encoder, is
  exact on every platform (#164). The conversion took swscale's fast path, which lands up to 3 off
  the colour matrix from YUV to RGB, repeats each chroma sample across a pair of pixels in a row,
  and gave one colour different bytes on each architecture, and in a frame with an odd number of
  rows from one with an even number. Every 8-bit result is now within 1 of the matrix, with chroma
  interpolated, and the same on every architecture, with the swscale flags mpv uses by default.
  From YUV to RGB a 1080p frame takes about 11 ms on x86-64 where it took under 1, from RGB to YUV
  about an eighth longer, and between YUV formats about the same. `WebRgbaConverter`, which
  converts a frame for every picture it draws, keeps the fast conversion.
- An MP4 or MOV that carries both Google's and Apple's 360 degree and stereo boxes reads as one
  description, whichever order the boxes come in (#160). FFmpeg's own writer puts both sets into
  an MP4 under `-strict unofficial`, and its reader read both into the same mapping and stereo
  layout, so the later box won: Apple's projection box after Google's spherical video box lost the
  yaw, pitch and roll, Apple's eyes box set the packing to unspecified over the one Google's box or
  Apple's own pack box gave, and Google's stereoscopic box after Apple's failed the whole header,
  so a file FFmpeg 6.1 opens did not open. The reader now adds the two up. Google's mapping is kept
  when there is one, because it also carries the turn and any bounds or padding, and Apple's
  projection is used when there is not. Apple's primary eye, baseline and disparity adjustment
  join Google's packing when the two agree on it, Google's stereo layout is kept whole when they
  disagree, as VLC, which reads only Google's boxes, shows it, and the field of view joins either
  way. The fix is the FFmpeg patch `0009-mov-add-up-the-360-and-stereo-boxes.patch`, so it reaches
  a platform only with an FFmpeg tree built from it, and the trees of 0.4.0 do not carry it.
- A remux of MPEG-4 Part 2 video into MPEG-TS can be decoded (#159). An encoder writing MPEG-4
  Part 2 for MP4 or Matroska puts the headers that give the picture size and coding in the
  extradata alone, MPEG-TS has nowhere to carry extradata, and FFmpeg's writer copied such a stream
  as it came, so neither this library nor FFmpeg itself could decode a single picture of the
  result. The writer now puts the headers in front of each keyframe, as it already did for H.264
  and HEVC, and leaves alone a keyframe that already begins with them, so a copy out of MPEG-TS
  and back into it comes through unchanged. The fix is the FFmpeg patch
  `0007-mpegts-carry-the-mpeg4-headers-in-the-stream.patch`, so it reaches a platform only with an
  FFmpeg tree built from it, and the trees of 0.4.0 do not carry it.
- `TrackSelector` compares languages, not strings (#158). A stream's language arrives as a
  two-letter code or tag from HLS, DASH and Matroska, as the terminology code from MP4 and as the
  bibliographic one from MPEG-TS and older Matroska files, and a preference for `eng` missed an
  `en` stream, `ger` missed `deu` and `zh` missed `chi`. A preference now matches every spelling
  of its language. Among the streams of that language, the one whose script and then region agree
  comes first, then one that names neither, then one that names another, so `pt-BR` takes a
  `pt-BR` stream over a default `pt-PT` one, and a region implies the script of Chinese, so
  `zh-TW` agrees with `zh-Hant`. Preference order still comes before closeness, a related language
  such as Middle English `enm` is not English, and `und`, `mul`, `mis`, `zxx` and `qaa` to `qtz`
  match nothing. The rules follow mpv's, which also folds every spelling of a language into one.
- A multiplex no longer plays one channel's picture with another channel's sound (#165).
  `TrackSelector` picked video and audio each from the whole stream list, so a transport stream
  whose radio channel came first paired the television's picture with the radio's sound, and a
  language preference took the sound of whichever channel spoke it. A new
  `selectAudio(streams, programs, video)` chooses only among the sound of the picture's own
  programme, falling back to a stream in no programme when that programme has none, and
  `primaryAudio` uses it. mpv keeps the tracks beside its video to that video's programmes the
  same way, and a source with no programmes selects as before.
- An MP4 or MOV track reports its extended language tag, the `elng` box, as
  `StreamInfo.language`, and a remux writes one (#157). ISO/IEC 14496-12 and QuickTime keep a whole
  BCP 47 tag such as `zh-Hant` in that box beside the three-letter code in `mdhd`, and Apple's
  frameworks read it as a track's extended language tag, but FFmpeg neither read nor wrote it, so a
  Traditional and a Simplified Chinese track both read `zho`. A track with a non-empty `elng` now
  reads that tag, and a track without one reads `mdhd` as before. A stream whose tag says more than
  a code can, with a script, a region or another subtag, or a language `mdhd` has no code for, also
  gets an `elng` box, which GPAC's MP4Box reads back; a stream tagged with a code alone, or with a
  two-letter code that has a three-letter one, writes none, so its output is unchanged. The fix is
  the FFmpeg patch `0008-mov-read-and-write-the-extended-language.patch`, so it reaches a platform
  only with an FFmpeg tree built from it, and the trees of 0.4.0 do not carry it.
- A Matroska track reports its `LanguageBCP47` as `StreamInfo.language` (#150). MKVToolNix writes
  that element on every track, beside the old three-letter `Language`, and the Matroska
  specification says a reader that knows it ignores `Language`. FFmpeg's reader skipped it, so a
  Traditional and a Simplified Chinese track both read `chi`, both regions of Portuguese `por`,
  and an English track, which MKVToolNix writes as `en` alone, the default `eng`. A track whose
  tag says `und` has no language, and a track without the element reads `Language` as before.
  Chapter languages are not read, because FFmpeg exposes none. The fix is the FFmpeg patch
  `0006-matroska-read-the-bcp47-language.patch`, so it reaches a platform only with an FFmpeg tree
  built from it, and the trees of 0.4.0 do not carry it.
- A remux keeps a language that a BCP 47 tag names (#156). An HLS rendition, a DASH representation
  and a Matroska track written by MKVToolNix name a stream's language with a tag such as `pt-BR`,
  or `en` alone for English, and FFmpeg's MP4, MOV and MPEG-TS writers, whose field holds a
  three-letter ISO 639-2 code, left such a stream with no language at all, while Matroska wrote the
  tag where a code belongs. Each now writes the code of the language the tag names: the
  terminological one into MP4, as its specification asks, the bibliographic one into MPEG-TS and
  Matroska, and either one MOV's table holds. Matroska also writes the whole tag as
  `LanguageBCP47`, except WebM, which has no such element. A three-letter code is written as
  before. FFmpeg's language table could not map `zh`, `zu`, `yo` or `za` at all, because six
  deprecated codes sit out of order at the end of the part it searched by halves; it now reads
  every entry. The fix is the FFmpeg patch `0005-write-a-bcp47-language-as-its-iso639-code.patch`,
  so it reaches a platform only with an FFmpeg tree built from it, and the trees of 0.4.0 do not
  carry it.
- A backward keyframe seek lands on the last keyframe that shows at or before its target (#155).
  FFmpeg finds a keyframe by when it decodes, and with B-frames a keyframe decodes before it shows.
  MP4's reader, which turns the target into a decode time by one constant, took a keyframe that
  showed after the target in an open group of pictures, FLV's, which seeks by decode time, did so
  with any B-frames, and MPEG-TS, which seeks by byte position, landed among pictures whose first
  keyframe showed late on nearly every seek. A cut then started late and lost the pictures between its target and that
  keyframe. `MediaSource.seekMicros` and a backward `PacketReader.seek` now read on to the keyframe
  they land on in the first video stream that is not a cover picture, and aim earlier when it shows
  too late. The packets read on the way are handed to the reads after the seek, so a seek FFmpeg
  landed right costs no second read and no second seek, and that stream's packets before the
  keyframe, which a byte-position seek lands among, are left out. Keyframes more than 32 MB of
  input apart are not checked. A stream a reader selects after a seek and before its first read
  starts where the seek would have landed it. A read through the C layer no longer hands out a
  packet of a stream turned off, which MPEG-TS did for one it had begun while the stream was on.
  The web seeks a whole source with every stream selected, so the check runs there too; the `web`
  zip of 0.4.0 does not carry the check. The C ABI does not change.
- Copied audio from a container whose clock is coarser than its samples keeps every sample in its
  place (#154). Matroska and FLV stamp whole milliseconds, so the packets of AAC at 48 kHz, 21.333
  ms each, are stamped 21 or 22 ms apart, and a copy into MP4 rescaled each rounded time on its
  own: the packets sat 1008 or 1056 samples apart, the last lasted 1008, and the sound ended 16
  samples short. The copy also asked the muxer for the source's millisecond clock, so an MP4's
  movie clock, and with it the length its edit list states, counted milliseconds too. A remux or a
  transcode copying such a stream now counts samples. Each packet starts where the one before it
  ended and lasts as many samples as FFmpeg reads from its codec. A packet whose own time disagrees
  with the count by more than one and a half ticks of the source's clock is a real gap and keeps
  that time. The copy asks the muxer for the sample rate as its clock. A source that states every
  sample exactly, as MP4 does, keeps its own times. The C ABI is 3.24 and adds
  `ffkmp_codecpar_audio_frame_samples` and `ffkmp_packet_set_duration`.
- A whole remux plays as its input does (#153). A copy's output started at the decode time of the
  first packet it wrote, which for video with B-frames comes before any picture shows and for AAC
  is the encoder's priming, so the output played every frame late by the difference, a frame for
  video with B-frames and 1024 samples for AAC, and the priming the input hid was heard.
  The output now starts where its media starts to show, as FFmpeg reads a stream's start: the
  earliest picture, and the first sound after the samples a stream skips. The sink holds the first
  packets until every audio and video stream has shown that, at most a second of the input. A cut
  that starts on a keyframe also leaves out the pictures that show before it, which lean on a
  picture the cut does not copy and used to move the output's start before its first picture. The
  C ABI is 3.23 and adds `ffkmp_packet_is_discard` and `ffkmp_packet_skip_start`.
- A remux or a transcode that copies video and is cut between keyframes keeps each chapter on its
  own frame (#144). The copy starts at the keyframe before the cut and that keyframe becomes zero,
  but the chapters were moved by the requested start, so each came out early by the distance from
  the keyframe to the cut: a chapter on a frame three seconds into the output said one and a half.
  The chapters are now placed when the header is written, against the origin the copied or encoded
  media actually took, so the chapters that cover the frames before the cut are kept as well. The
  header is therefore written by the first packet rather than before the first is read.
- A filter graph hands each frame over as it comes out, before it asks the graph for the next one
  (#141). `feedInput`, `flushInput` and `process` took every frame the graph had ready first, so a
  filter that expands a short input held all of its frames before the first was seen, and one that
  never stops producing, such as `tpad=stop=-1` once its input ends, never reached its callback:
  nothing could stop it, a `Transcoder` cancelled inside `withTimeout` included, and it filled
  memory. A callback now ends the call by throwing or by closing the graph, and `process` checks
  for cancellation before each frame it emits, closing the frame that check stops. A frame handed
  over is still the callback's only for the call, and the collector's to close, as before.
- A transcode keeps the last frame of a stream whose frames each claim one unit of its time base
  when they lie further apart. That is the duration FFmpeg makes up for a stream that states none,
  and since #143 what a stream written at a fine constant rate to place frames at uneven times
  states, and a transcode took it as it stood and dropped the last frame. A duration of one unit
  after a gap more than twice as long now counts as made up and the gap stands in for it, as
  FFmpeg 9's own command line does; FFmpeg 6.1's still took it as it stood.
- An encoded video keeps its last frame (#143). FFmpeg's encoders hand back packets with no
  duration, and a `MediaSink` video stream declared no frame rate, so the muxer could not give the
  last packet one: an MP4 or MOV ended on a sample of length zero that players drop, 14 frames at
  10 fps played as 13, a single frame played as none, and a Matroska file stated a length one frame
  short. The stream now declares `VideoEncoderSpec.frameRate` as its average rate, as FFmpeg's
  command line does, and the muxer gives each packet one frame at that rate. The C ABI is 3.22 and
  adds `ffkmp_stream_set_avg_frame_rate`.
- On the web, a codec module out of memory no longer has strings and out-slots written at address
  zero, or captions, extradata and metadata read from there as if they were the answer (#142). The
  module grows its memory, so a failed allocation returns zero instead of aborting; every
  allocation the backend makes now refuses that with `FFmpegError.OutOfMemory`, and an open that
  fails part way gives back what it took before the failure, the byte source included.
- `SubtitleDecoder.drain()` gives what a subtitle decoder still holds once the packets of its
  stream have run out (#149). FFmpeg's CEA-608 caption decoder gives a caption only when the screen
  next changes, and teletext holds its page the same way, so the caption on screen at the end of a
  stream used to be lost; the drain gives it, and every other decoder drains to null. The C ABI is
  3.19: `ffkmp_subtitle_decode` takes a NULL packet as the drain, where it refused one before.
- `MediaSink.addCopyStream` refuses a source opened from the file the sink writes, with
  `FFmpegError.InvalidArgument`, before anything is added or the file is touched (#146). The
  sink truncates its file when it writes its header, so a tee or a recording taken into the file
  being read destroyed it while the source went on reading its buffers. The identity is the one
  `Remuxer` and `Transcoder` already refuse (#47), and every refusal of the three now also sees
  through a `file:` prefix on either side.
- A playlist of WebM or Matroska segments seeks after it has been read to its end. FFmpeg's
  Matroska reader kept answering end of file once the HLS reader had reset its input, so every
  later seek returned and no packet followed. The fix is the FFmpeg patch
  `0003-matroska-read-on-after-the-input-moves.patch` (#125).
- An HLS subtitle rendition turned on in the middle of a cue delivers that cue. FFmpeg catches a
  rendition that starts late up to the newest packet it read, and it dropped every cue that began
  before that moment, the one on screen among them. A cue now arrives while it is still showing at
  that moment. The fix is the FFmpeg patch `0004-hls-keep-the-subtitle-cue-on-screen.patch`
  (#126).
- Both fixes live in FFmpeg, so they reach a platform only with an FFmpeg tree built from these
  patches. The trees of 0.4.0 do not carry them.
- `AudioEncoder.drive` closes a frame it cannot convert. When FFmpeg refused to build the
  converter, as it does for nine channels in no named order sent to a stereo encoder, the refusal
  was right but the frame stayed open with its buffers. So did a frame that arrived while the
  encoder failed on the samples the previous converter still held (#127).
- On Kotlin/Native, `MediaSource.open` closes a byte source whose `size` or `seekable` throws while
  it opens. The open took the source first and read both properties outside the scope that closes
  it, so the source, its two references and a 64 KiB scratch buffer stayed alive for the rest of
  the process. The getter's own exception reaches the caller, with a close that fails as well
  added as suppressed (#131).
- `AudioEncoder.drive` hands the encoder every sample in the order it came. A sample rate change
  holds some samples back, and they waited in the converter until the input ended, so they reached
  an encoder that takes any chunk size, such as PCM, after every newer frame that needed no
  conversion. A converter kept across that stretch also dated what it converted next from before
  it. The held samples now go to the encoder before the next frame that takes another path, and
  that frame starts a converter of its own (#128).
- On the web, `Frame.info` reports each frame's own `sampleAspectRatio` and `channelLayoutMask`,
  as the JVM and native backends do. Both kept the defaults of `FrameInfo`, so every web frame
  said square pixels and no layout, and an anamorphic picture drew with square pixels (#130).
- On the web, calls to `KiteFFmpegWeb.load` that overlap share one fetch. Each call used to build
  its own instance of the codec module, with its own wasm memory, and only the first to land was
  adopted. Every overlapping call now waits for the first one's module, a call that is cancelled
  leaves the others waiting, a load that fails is forgotten so that the next call tries again, and
  a load of another address while one is in flight is refused with an error that names it (#132).
- A `Resampler` whose `AudioSpec` names no layout takes a decoded frame of 10, 12, 14, 16 or 24
  channels in FFmpeg's default layout for that count. The check compared the frame with a copy of
  FFmpeg's defaults in Kotlin that stopped at eight channels and answered 0 above, so it refused a
  frame the C side would have converted. The default now comes from the linked FFmpeg through the
  new C helper `ffkmp_ch_layout_default_mask`, and the C ABI is 3.17 (#129).

## [0.4.0] - 2026-09-29

HLS through your own HTTP client, subtitle conversion and subtitle files, more filters, the
iPhone's hardware encoders, and closed captions. The FFmpeg builds carry a patch to the HLS
demuxer. The upgrade notes come first.

### Upgrading

- Not binary compatible with 0.3.0: `StreamInfo` and `DemuxOptions` gained fields, which changes
  their constructors and `copy`. Rebuild a library compiled against 0.3.0.

- `StreamInfo` gains `mirrored`, which changes the generated data-class methods. Recompile. A
  renderer mirrors the picture left to right first when it is true, and then turns it clockwise by
  `rotationDegrees`. The C ABI is 3.11, which adds `ffkmp_stream_mirrored` (#81).
- `DemuxOptions` gains `format`, which changes the generated data-class methods. Recompile. It
  names the demuxer to use, as `ffmpeg -f` does, so headerless input such as `s16le` or
  `rawvideo` opens (#82).
- The JVM jar is Java 11 bytecode, checked against the Java 11 API, where it was Java 21. An
  application on Java 11 or 17 can now load it. The API is unchanged (#103).
- FFmpeg's own log lines no longer reach stderr. `FFmpeg.setLogSink(level, sink)` routes them to a
  sink of your own, with the level, the name of what logged and the message. The sink runs on the
  thread that logged, on every backend. The C ABI is 3.12, which adds `ffkmp_log_set_sink` (#83).
- `Transcoder.transcode` and `Remuxer.remux` gain two overloads each. One takes pre-open options
  for the input path, which is how an Android app reads a picked file through `"fd:"` without a
  copy. The other reads from a `MediaByteSource` factory and writes into a `MediaByteSink`, with
  the container named by `format` (#85).
- The C ABI is 3.13, which adds `ffkmp_frame_convert_display`, the conversion that
  `WebRgbaConverter` uses (#120).
- The C ABI is 3.14, which adds `ffkmp_frame_a53_cc`, the reader behind `Frame.closedCaptions`
  (#84).
- The filter DSL no longer offers `eq()` or its `Eq` step. No KiteFFmpeg build carried `eq`,
  because FFmpeg builds it only under the GPL, so a chain that used it failed on every build. Use
  `hue`, `colorlevels` or `curves` through `raw()`, which every build carries (#77).
- The byte-source `MediaSource.open` gains `url`, `mimeType` and `nestedOpener`, after
  `interrupt`. The old overload stays, hidden, so code compiled against 0.3.0 still links. The C ABI
  is 3.16, which adds `ffkmp_fmt_open_input_io2`, `kc_io_opener` and
  `ffkmp_fmt_nested_io_available` (#76).

### Added

- An HLS playlist opens through a `MediaByteSource`, including over https. Pass `url`, and
  `mimeType` when the url has no `.m3u8` name, so that FFmpeg's probe recognises the playlist. Pass
  `nestedOpener`, a new `MediaByteOpener`, to serve the variant playlists, segments and keys the
  playlist names, for example through your own HTTP client. KiteFFmpeg decrypts AES-128 segments
  itself. The addresses come from the playlist, so the opener decides which ones open. This needs a
  small patch to FFmpeg's HLS demuxer, which the FFmpeg builds of this release carry; an FFmpeg
  build without it refuses the opener with `FFmpegError.Unsupported`. On the web, `url` and
  `mimeType` reach the probe, and `nestedOpener` fails the open with `FFmpegError.Unsupported`
  (#76).
- Every build carries the `crop`, `transpose`, `hflip`, `vflip`, `fps`, `drawbox`, `fade`,
  `setsar`, `setdar` and `pan` filters, so the filter DSL's `crop()`, `transpose()`, `fps()`,
  `drawBox()` and `pan()` steps build (#77).
- Every build carries the `srt`, `ass` and `webvtt` muxers, so `Remuxer.remux` writes a text
  subtitle track to its own `.srt`, `.ass` or `.vtt` file. The builds also carry the `mov_text`,
  SubRip, `ass` and `webvtt` encoders, which `subtitleCodec` uses (#86).
- `Transcoder.transcode` gains `subtitleCodec`, which converts every subtitle stream to a text
  codec instead of copying it, so a SubRip track from an MKV reaches an MP4 as `mov_text`. An image
  subtitle cannot become text and fails with `FFmpegError.Unsupported`. `CodecId` gains `MovText`,
  `SubRip`, `Ass` and `WebVtt`. The parameter sits after `subtitleCopy`, and a nullable `CodecId`
  changes the JVM names of the three overloads, so recompile. The C ABI is 3.15, which adds the
  `ffkmp_subtitle_converter_*` functions (#86).
- The iPhone build carries the `h264_videotoolbox` and `hevc_videotoolbox` encoders, as the macOS
  build does, so `EncoderId.H264VideoToolbox` and `EncoderId.HevcVideoToolbox` work on an iPhone.
  The simulator builds stay without them, because VideoToolbox encode is not available there
  (#78).
- `Frame.copyPlanesInto(destination)` copies the same bytes as `copyPlanesToByteArray` into an
  array that the caller keeps. `Frame.planesByteCount()` gives the size that array needs. A
  converter that reuses one array allocates nothing per frame (#122).
- `Frame.closedCaptions()` returns the CEA-608 and CEA-708 captions a video frame carries, as the
  cc_data triplets of its ATSC A/53 side data, or null when it carries none. A caption track
  stored on its own, such as a MOV `c608` track, already decodes to text through
  `openSubtitleDecoder`, and a test now covers it (#84).

### Fixed

- The subtitle chain that KitePlayer links, the `ass-chain` release assets, builds HarfBuzz
  14.5.0 instead of 14.2.1. HarfBuzz's own notes for 14.4.0 and 14.5.0 list fixes for crashes and
  hangs with malformed fonts, and a video file can carry its own fonts.
- `copyPlanesToByteArray` of a frame with no picture and no samples returns an empty array on the
  JVM, Android and the web, as the documentation says. It threw `FFmpegException` there (#122).
- On the web, `WebRgbaConverter` tone maps a PQ or HLG picture to SDR. It drew the code values,
  flat and dim. BT.2020 primaries fold to BT.709, and luminance rolls off from a 1000 nit peak to
  203 nit reference white, the law KitePlayer's other software paths use. A YCgCo picture draws
  with the YCgCo matrix, where it used the HD or SD guess (#120).
- A picture tagged with the FCC matrix converts with that matrix on every backend. It used the
  BT.709 or BT.601 guess by height (#120).
- A `MediaByteSource` whose `read` answers with more bytes than it was asked for fails the operation
  with an I/O `FFmpegException` on every backend, before any of those bytes is used. The cause names
  the two counts.
- `Transcoder.transcode` checks for cancellation before every frame it encodes, so a cancel, or an
  exception from `onProgress`, also stops a transcode inside a long gap between two video frames.
  A frame whose end is past what a `Long` holds lasts one output frame.
- A `MediaSink` close waits for an encode or a copy-stream write that already started on another
  thread, and a second close waits for the first to finish. Once a close starts, a new encode, copy
  write, metadata call or chapter call fails with `IllegalStateException`. A close from inside the
  sink's own `MediaByteSink` call fails with `IllegalStateException`, which fails that write.
- `Transcoder.transcode`, `Remuxer.remux`, `MediaSource.seekMicros`, `MediaSource.extractFrame` and
  both `drive` overloads declare `@Throws`, so Swift and Objective-C receive an `FFmpegException`
  as an error instead of terminating (#87).
- On the JVM and Android, a file whose tags are not valid UTF-8, such as a Latin-1 ID3v1 title or
  a RIFF INFO value in a code page, opens. Each malformed sequence reads as U+FFFD, as it already
  did on the native and web backends (#75).
- An open option key that is not a valid string fails with `FFmpegException` and makes no JNI call
  while that failure is pending, so Android's CheckJNI no longer aborts a debuggable app (#104).
- A thread that converted a frame's pixel format no longer leaks its cached scaler when it ends
  (#105).
- `MediaSource.close` and `MediaSink.close` run every release even when one step throws: a byte
  source close that throws no longer leaves the open's interrupt bound, and a failed flush packet
  allocation no longer skips the encoders, the trailer and the byte sink (#112).
- On the web, `MediaSource.decodeStreams` refuses a list that names one stream twice before it
  opens any decoder. Each refused call used to leave one decoder allocated (#113).
- On the web, opening a `MediaByteSource` uses the byte count it staged and no longer reads
  `size` again after closing the source, so a source that refuses access after close opens (#114).
- `bufferFrames` closes a frame that a cancelled collector took from its buffer. A frame belongs
  to the collector once its `emit` is called, as before (#115).
- The interrupt flag behind `OpenInterrupt` is read and written atomically, so raising it from
  another thread while an open polls it is no longer a data race (#116).
- `PacketReader.seek` with `SeekDirection.Forward` lands at or after the target on MP4, Matroska,
  MPEG-TS, AVI and FLV. It landed on the keyframe before the target, because the window it passed
  had no floor and those demuxers take the nearer side as the direction (#80).
- On the web, `Packet.durationMicros` is null for a duration that is not positive, as on the JVM
  and native backends. A negative duration used to pass through (#106).
- On the web, `FFmpeg.hasFilter` answers false, because the web builds no filter graph. Every
  filter graph build and `FilterChain.requireAvailable` refuse with one reason that says so. The
  refusal used to say that decoding was not implemented, which is false on wasmJs (#102).
- A display matrix that mirrors the picture is reported as a mirror. A left-right mirror used to
  read as a half turn, so a renderer showed the video upside down. It now reads as no turn with
  `StreamInfo.mirrored` set (#81).

## [0.3.0] - 2026-09-25

FFmpeg 9.0.2, subtitle decoding, output into your own bytes, libswresample, and one filter graph
for every backend. The breaking changes come first.

### Breaking

- **`CodecId` names a format only.** Encoders and decoders have their own types, `EncoderId` and
  `DecoderId`, and `FFmpeg.codecOf`, `FFmpeg.encodersFor` and `FFmpeg.decodersFor` map between
  them. `VideoEncoderSpec.encoder` and `AudioEncoderSpec.encoder` take an `EncoderId`, and
  `MediaSource.openDecoder` takes a `DecoderId`. The implementation constants on `CodecId` are gone.
- **`FilterGraph.feedInput` and `flushInput` return a `FeedResult`**: `Ready`, or
  `NeedsInput(index)` when a graph with several inputs waits for one of them.
- **Feeding a flushed filter input throws `IllegalStateException`.** A graph that `process()` spent
  names that reason on every later call.
- **NVENC refuses `crf`.** NVENC has no such option (its option is `cq`); it used to pass silently.
- **The C ABI is 3.10** (0.2.0 shipped 2.7). This matters only to code that calls the C helpers
  directly.

### Binary compatibility

0.3.0 is not binary compatible with 0.2.0. Source that called the old signatures compiles
unchanged, but a library compiled against 0.2.0 fails when an app resolves 0.3.0: with
`NoSuchMethodError` on the JVM and Android, and at link time on native targets. Rebuild every
library that depends on kiteffmpeg against 0.3.0. These compiled signatures changed:

- `MediaSource.open(path, options)` and `MediaSource.open(io, options)` gained `interrupt`.
- `Transcoder.transcode` and `Remuxer.remux` gained `dispatcher`.
- `FilterGraph.buildAudio`, `buildAudioMulti` and the DSL `buildAudio` gained a channel layout mask.
- `FilterGraph.feedInput` and `flushInput` return `FeedResult` instead of `Unit`.
- `MediaSource.openDecoder` takes a `DecoderId` instead of a `CodecId`.
- The constructors and `copy` of `Disposition`, `VideoEncoderSpec`, `AudioEncoderSpec`,
  `AudioInput`, `StreamInfo`, `VideoStreamInfo` and `FrameInfo` gained fields.
- The implementation constants on `CodecId`, such as `CodecId.Libx264`, are gone.

### Added

- **FFmpeg 9.0.2.** Every prebuilt FFmpeg tree is built from it (was 8.1.2).
- **Subtitle decoding.** `MediaSource.openSubtitleDecoder(stream)` decodes Blu-ray, DVB and DVD
  image subtitles to premultiplied RGBA `SubtitleImage`s with their position and canvas size, and
  text subtitles to their ASS events.
- **Output into your own bytes.** `MediaSink.open(sink, format)` takes a `MediaByteSink`. A sink that
  cannot seek takes streamable containers such as fragmented MP4; a container that must seek refuses
  it and names the option to set. The sink's own exception is the cause of the error it causes.
- **libswresample.** `Resampler(input, output)` converts rate, channel layout and sample format
  between two `AudioSpec`s. `AudioEncoder.drive` now converts a frame's sample format and channels on
  its own, and its rate too for codecs that take any chunk size.
- **What a build contains.** `FFmpeg.components(kind)` lists the decoders, encoders, demuxers,
  muxers, filters, protocols and bitstream filters the linked FFmpeg has.
- **Typed demuxer options**, `DemuxOptions`, for the open-time options callers need most. The two
  MP3 keys that break seeking, `usetoc` and `fastseek`, are refused.
- **Audio track choice by language.** `TrackSelector` picks by the caller's languages, then by the
  default flag, and never auto-picks descriptive audio or commentary. `Disposition` gains
  `descriptions` and `comment`.
- **An open you can interrupt.** Pass an `OpenInterrupt` to `MediaSource.open` and call `interrupt()`
  from another thread to stop an open that waits on a stalled input.
- **Codec profile** on `StreamInfo.codecProfile`, so a player can tell AAC LC from HE-AAC.
- **The web codec module ships.** `kite.mjs` and `kite.wasm`, with their licence texts, travel as
  the `web` zip of the `wasmJs` artifact. The README says how to serve them.

### Changed

- **An encode keeps colour, HDR metadata, pixel shape and channel layout.** `Transcoder` copies what
  the first encoded frame declares into a spec that leaves them null, and writes HDR10 mastering
  display and content light level metadata (`HdrMetadata`, `MasteringDisplay`, `ContentLightLevel`).
- **A remux keeps stream tags, disposition and chapters.** `MediaSink.setChapters` writes chapters.
- **The transcoder's filter graphs follow one rule on every backend**: built from the stream's
  declared shape, and rebuilt when a frame's size, pixel format, pixel shape, rate, sample format or
  channel layout differs.
- **Filter callbacks run outside the graph lock**, so a callback may call back into its graph,
  `close()` included.
- **A remux runs on `Dispatchers.IO`**, or on the dispatcher you pass, not on the caller's thread.
- **Frame and packet bytes copy in one step.** On Kotlin/Native a 1080p `copyPlanesToByteArray` went
  from about half a second to about 0.3 ms in a debug build. On the JVM reading a frame copies once
  instead of twice, and `Frame.ofVideo` and `Frame.ofAudio` copy once instead of three times.

### Fixed

- AAC files written here no longer play the encoder's priming samples at the start.
- Re-encoding 5.1 audio with side surrounds, which is what AC-3 decodes to, no longer fails with
  "Invalid argument", and neither does a trim that cuts inside such a block.
- A stream that switches from stereo to 5.1 midway no longer fails to transcode on the JVM, and pixels
  that change shape midway are no longer filtered as square on either backend.
- Every web playback ended with an I/O error at the end of the file instead of ending the stream.
- An all-zero display matrix reports an upright rotation on every architecture.
- A closed packet, reader, decoder or source throws `IllegalStateException` on the web too.
- A byte source's exception is the cause of the resulting error on the native backends and the
  web too. On the web it used to reach the caller bare instead of inside an `FFmpegException`.
- On the JVM, every handle close scanned the whole handle table while a filter graph was open.

### Internal

- The build uses the Android Gradle plugin 9.4.0 (was 9.2.1) and Gradle 9.7.1 (was 9.6.0), the same versions as KitePlayer.
- CI runs the JVM tests, and the JNI build fails when a registered C function's signature differs
  from its descriptor.
- Comments and docs no longer cite internal plan codes, and the C layer README describes the layer as
  it is.
- One decode contract runs on the JVM, macOS and the web against the same H.264 and AAC file, with
  every expected value pinned. A new CI job links the real web codec module and runs the web tests
  against it, so the web backend is tested on real media and not only on a fake module.
- The browser demo links the same module that ships, and the Android device tests build again.

## [0.2.0] - 2026-09-04

Six new calls, one breaking rename of a parameter type, and one fixed frame-ownership bug that
could hand you a frame the library had already freed.

```kotlin
implementation("io.github.yuroyami:kiteffmpeg:0.2.0")
```

### Added

- **Read a file's details without opening a handle.** `MediaSource.probe(path)` opens, reads and
  closes in one blocking call and answers a `MediaProbe`: format name, duration, start time,
  container bit rate, seekable, metadata, chapters and every stream. It is a value, so there is
  nothing to close and nothing to leak. There is a second overload over a `MediaByteSource` for
  bytes you already hold. Use it for a file browser, a playlist scan or a duration check; use
  `MediaSource.open` when you are going to read frames.
- **Field order**, on `VideoStreamInfo.fieldOrder`, so an auto-deinterlace has something to decide
  from. It is a display order: `TopFirst`, `BottomFirst`, `Progressive` or `Unknown`. `Unknown` is
  not a synonym for progressive. Most files say nothing, and treating silence as progressive skips
  deinterlacing on material that needs it.
- **Container bit rate**, on `MediaSource.bitrateBps` and `MediaProbe.bitrateBps`. The stat that
  used to always answer null now has a source.
- **A container that lies is on the record.** `MediaSource.streamDivergences` lists the fields
  where the header disagrees with what the decoder actually produced: width, height, pixel format,
  sample rate or channels. A mislabelled resolution used to show up as a wrong-sized surface with
  no way to find out why. This is not an error, the file still plays, and the decoded numbers are
  the ones to trust.
- **A filter chain says what your build is missing.** `FilterChain.missingFilters()` answers in
  chain order, without repeats, and `requireAvailable()` refuses with `FFmpegError.FilterNotFound`
  naming every missing filter at once. `FilterGraph.buildVideo` and `buildAudio` gain chain
  overloads that check before FFmpeg parses anything, so you no longer learn this from a parse
  error about a string.
- **Push your own packets into a muxer.** `CopyStream.write(packet)` lets a caller who drives its
  own read loop join a reader to a sink, which previously only `Remuxer` and `Transcoder` could do.
  It writes a reference, so your packet comes back open and unchanged. Ordering stays yours:
  packets must reach the muxer in the order the stream expects.

### Changed

- **Breaking: `aformat` takes a typed sample format.** `AudioFilterBuilder.aformat` and
  `AudioFormat.sampleFormat` were `String?` and are now `SampleFormat?`. A misspelled format used
  to survive until the graph was parsed. `SampleFormat("fltp")` reads the same as the old string,
  so the fix is one wrapper per call site.
- **Breaking: `VideoStreamInfo` gained a field**, so its generated `copy()` has a new signature.
  Source-compatible if you use named arguments; recompile if you call `copy()` positionally.
- **A missing filter throws `FFmpegError.FilterNotFound`**, not `FFmpegError.Internal`. The old
  type was not one a caller would catch for this.
- **The identity gate now lives in C**, so the eighteen constructor helpers refuse before they
  build anything. Kotlin callers were already gated; a pure C or JNI consumer was not, though the
  header said otherwise. Pointer-returning helpers answer NULL, int-returning ones answer
  `AVERROR_EXTERNAL`, and `kc_ffmpeg_report_get` carries the reason. The C ABI minor moves 6 to 7.

### Fixed

- **A cancelled filter emission no longer frees your frame.** `process(input).first()` used to hand
  back a frame the JVM backend had already closed, so every read on it refused; the native backend
  leaked the clone instead. Neither backend can tell "the collector took it" from "the scope died",
  because both arrive as the same cancellation, so both now leave an emitted frame alone. The cost
  is one refcount bump on a path that ended early.

### Documentation

The README and all six guides were rewritten for someone who does not already speak FFmpeg. The
glossary comes before the jargon, the install snippet is the three lines you actually add, and the
task table asks what you want to do rather than naming the FFmpeg stage. Six pages still told you
to configure a Gradle plugin deleted in August, and the docs landing page still said nothing was
published. Both are gone. `wasmJs` is documented as what it is, a real playback backend, with `js`
as the placeholder.

### Internal

- Fuzz targets for the muxer name and the three codec-name lookups, 25 new corpus seeds.
- A CI ratchet on how dependencies resolve: no dynamic versions, no inline coordinates, no
  per-module repositories, no `mavenLocal`, no plain http, and a wrapper pinned by sha256.

## [0.1.0] - 2026-08-30

**The project was renamed.** KiteCodec became KiteFFmpeg on 2026-08-29, because the name now says
what it is: a binding for FFmpeg, which is the only media engine it will ever wrap.

The artifact is `io.github.yuroyami:kiteffmpeg` and its version line starts again at 0.1.0.
That number is LOWER than the old `kitecodec-core` 0.1.3 and is NEWER than it: a new artifact id
starts its own line. The old coordinates stay on Maven Central exactly as they are and receive
nothing further.

```kotlin
implementation("io.github.yuroyami:kiteffmpeg:0.1.0")
```

Names beginning `kc_`, `ffkmp_` and `libkitecodec` inside the C layer are unchanged on purpose.
They are internal to the build and invisible to anything that depends on this library.

### Added

- **An interrupt seam.** `MediaSource.interrupt()` is the one member callable from another thread
  while a read, seek or decode is blocked, so a stalled network open no longer holds a thread until
  the socket gives up. One-way by design: an interrupted source is being abandoned, not paused, and
  there is no way to clear the flag, because a cancelled read resuming into freed state is the bug
  it exists to prevent. `close()` stays both legal and required.
- **Stream disposition**, exposed on `StreamInfo` as `default`, `forced` and `hearingImpaired`, so a
  player can pick the right subtitle track instead of guessing from the title string.

### Changed

- **FFmpeg n8.0 to n8.1.2**, rebuilt for all eleven triples with dav1d 1.5.4 inside every one.
- **A reader reselects its streams without moving its cursor**, so switching audio track no longer
  costs a seek.

### Carried from 0.1.3

Everything. The AAC encoder fix, the embedded-FFmpeg integration, the Android AAR, the real JVM
variant, mandatory dav1d and the portable profiles are all present and unchanged. See the 0.1.3
entry below for what each one was.

> Everything below this line shipped as `io.github.yuroyami:kitecodec-core`, the artifact id used
> before the 2026-08-29 rename. Those versions stay on Maven Central and receive nothing further.

## [0.1.3] - 2026-08-24

**Start here.** 0.1.0 and 0.1.1 are on Maven Central and stay there; 0.1.2 was tagged and never
published. This release supersedes all three, and everything they carried is listed below.

Consumer integration is one line, on every platform:

```kotlin
implementation("io.github.yuroyami:kiteffmpeg:0.1.3")
```

### Fixed in 0.1.3

- **AAC encoding was missing on Linux and Windows.** `aac` was enabled only in the Apple and
  Android profiles, so `Transcoder.transcode` with default audio settings failed with
  `No encoder named 'aac'` on those two platforms. Measured, not inferred: `ff_aac_encoder` was
  present in `macos-arm64` and absent from `linux-x64` and `mingw-x64`. It is now in the shared
  encoder list, and the companion binaries were rebuilt. Affected 0.1.1 and 0.1.2.

### Fixed, carried from 0.1.2 (never published)

Each of these was broken on some platforms and correct on others, which is why none of them looked
like bugs.

- **Audio encoders accepted video frames** (JVM, Android). The picture was encoded as sound instead
  of refused.
- **One file's stream was accepted by another file's remux** (JVM, Android). Matched by index alone,
  so the output carried the wrong time base and played at the wrong speed.
- **A missing encoder could not be caught by kind** (iOS, macOS, Linux, Windows). It threw an
  untyped internal error instead of `EncoderNotFound`, so callers could not fall back.
- **Video frames could be freed mid-draw** (iOS, macOS, Linux, Windows). `withPlanes` and
  `hardwareSurface` handed out pointers without holding the frame open.
- **`corruptDataSkipped` read zero during playback** (browser). Reset on every decode and written
  only at the end, so a second decode erased the first one's total.
- **Two leaks** (browser). A failed reader open leaked one codec context per stream; a failed
  decoder open wedged the source so it never opened another reader.
- **A failed `addCopyStream` left the writer usable** (all platforms). The muxer kept a
  half-configured stream and went on accepting calls.

### Carried from 0.1.1

- **FFmpeg lives inside the published klibs, and the Gradle plugin is gone.** Each native target's
  cinterop klib embeds the six libav\* archives plus libdav1d and carries its platform linker
  flags, so integration is the dependency line above and nothing else. The plugin module, its DSL
  and its `Local`/`System` consumer modes are deleted.
- **The Android AAR is a first-class artifact** (`kiteffmpeg-android`): self-contained
  `libkitecodec_jni.so` for `arm64-v8a` and `x86_64`, licence payload under `META-INF/licenses/`,
  consumer keep rules. Before this an `androidTarget` consumer resolved the JVM artifact and failed
  at first load.
- **A real JVM variant.** `jvmMain` used to compile the throw-everything placeholder, so every
  desktop consumer got a library whose entry points all threw. It builds the real tree now, with the
  host JNI library inside the jar.
- **dav1d is mandatory in every build**, so software AV1 decoding always works. All 11 native
  targets are published, and publication hard-fails if any configured target lacks its FFmpeg tree.
- **Every FFmpeg profile is portable**, macOS included, so a release asset links on a machine with
  nothing installed.

### Carried from 0.1.0

- **Apache-2.0 `LICENSE` and `NOTICE`.** The repository previously had no valid licence file at all,
  which made it legally unusable. `NOTICE` states the FFmpeg LGPL position and the obligations it
  puts on a consumer.
- **No GPL builds.** This project builds and publishes the LGPL flavour only; shipping a
  GPL-flavoured binary would make a consumer's whole application GPL-3.0, which is not a decision a
  library should make for them.

### Not tested

The three browser fixes and the `addCopyStream` fix ship without an automated test: there is no
browser test source set, and the muxer failure cannot be triggered through the public API. Every
other fix above landed with one.

### FFmpeg binaries

The companion zips for this release are on `ffmpeg-n8.0-r2`. The older `ffmpeg-n8.0` release is kept
unchanged, because `NOTICE` names it as the LGPL source offer for 0.1.0 and 0.1.1, which are on
Maven Central permanently. A consumer needs neither: FFmpeg is already inside the artifact.

## kitecodec-core 0.1.0 - 2026-08-21

First public release. The repository went public on this date; everything before it
was private and unpublished.

### Added
- `LICENSE` (Apache-2.0) and `NOTICE`. The repository had **no licence file at all**
  until now, which made it legally unusable by anyone. `NOTICE` states the FFmpeg LGPL
  position and what shipping it obliges you to do.

### Removed
- **All GPL FFmpeg build tasks and release jobs.** This project builds and publishes the
  LGPL flavour only. Distributing a GPL-flavoured binary makes the consumer's whole
  application GPL-3.0, which is not a decision a library should make on their behalf.
  `FFmpegLicense.GPL` survives as a LABEL for a tree you built yourself (it is a path
  segment and rides into the identity report); what is gone is KiteFFmpeg producing one.
  This also fixes the case where `portableDesktopArgs()` ignored the licence
  argument and wrote trees containing no GPL code into directories named `gpl` - a
  curiosity while private, a false public statement about licensing once published.

## [0.1.1] - 2026-08-22

**The first release on Maven Central**, and the first anyone can consume with one dependency line. 0.1.0 existed only as source and local artifacts; everything below shipped publicly for the first time in 0.1.1, The 11 FFmpeg companion zips live on the `ffmpeg-n8.0` release, one canonical copy shared by every KiteFFmpeg version, because they are keyed to the FFmpeg version rather than to this one; they are build evidence and the LGPL source offer, and a consumer needs none of them.

### Added
- **The Android AAR is a first-class published artifact** (`kiteffmpeg-android`): self-contained `libkitecodec_jni.so` for `arm64-v8a` and `x86_64` with FFmpeg + dav1d statically inside, the LGPL licence payload under `META-INF/licenses/`, and consumer keep rules. Before this, an `androidTarget` consumer resolved the JVM artifact and failed at first load.

### Changed
- **FFmpeg embedded: FFmpeg now lives INSIDE the published klibs, and the Gradle plugin is gone.**
  Owner decision 2026-08-22. Each native target's cinterop klib embeds the six libav\*
  archives plus libdav1d (the same `staticLibraries` slot `libkitecodec.a` always rode) and
  carries its platform linker flags, so the whole consumer integration is
  `implementation("io.github.yuroyami:kiteffmpeg:<v>")`. Proven the day it landed: a
  project with nothing but that line linked a macOS executable (which ran, identity gate
  green), an iOS simulator framework and a Windows PE32+ executable. The plugin module,
  its DSL (`source`/`license`/`dav1d`/`libass`/`repo`/`releaseTag`/`pinnedSha256`), its
  tasks and its docs page are DELETED; `Local` and `System` consumer modes die with it
  (a dev host without vendored trees still falls back to a system FFmpeg internally, via
  `ffmpeg-system.def`). The version-mismatch corruption class is gone by
  construction, so the plugin-side version gate went with it. Artifact POMs now declare
  Apache-2.0 + LGPL-2.1-or-later (embedded FFmpeg) + BSD-2-Clause (dav1d), the JVM jar
  carries the licence texts, and NOTICE states the consumer obligations.
- **The dav1d axis is dead: dav1d is mandatory in every FFmpeg build.** Measured in-app
  cost ~0.7-0.9 MB on arm64, ~1.6-2.0 MB on x86_64; without it FFmpeg plays zero AV1 in
  software. One flavour per triple (11 zips, `-dav1d` suffix gone), the two-way contract
  deleted, `--enable-libdav1d` now part of the recipe fingerprint, and every
  `buildFFmpegFor<Target>` depends on its `buildDav1dFor<Target>`.
- **All 11 native targets are published.** The stable/experimental publication split is
  gone; publication still hard-fails if any configured target lacks its FFmpeg tree.
- **Every FFmpeg profile is now PORTABLE, macOS included.** The fat macOS desktop profile
  (vpx/aom/opus/lame/webp encoders, the freetype/harfbuzz/fribidi/libass text stack, drawtext)
  is gone. Every one of those libraries had to come from Homebrew, Homebrew ships graphite2
  shared-only, and a Release asset that only links on a machine with Homebrew is not an asset.
  macOS now builds exactly like iOS (SDK zlib, VideoToolbox, AudioToolbox) plus the VideoToolbox
  encoders and the native aac encoder. Decoding is untouched: the read side is wide by class,
  and software AV1 is the dav1d flavour's job. A consumer's macOS link set shrinks to
  `-lz` plus the five media frameworks; an old fat Local tree reads as stale in
  `checkFFmpegRecipes` and rebakes portable.
- **Release assets moved to the KiteFFmpeg version tag.** Prebuilts now live on `v<version>`
  (`v0.1.0`), not on `ffmpeg-<ffversion>`. The plugin's new `ffmpeg.releaseTag` property
  defaults to the plugin's OWN version tag through a generated constant, so a plugin version
  always fetches the assets released with it. Every KiteFFmpeg release ships the FULL set:
  11 triples x 2 flavours (plain and dav1d) = 22 zips, built by `release-binaries.yml`.
- **`BuildDav1dTask` covers all 11 triples.** android-arm32, ios-x64 and macos-x64 gained
  cross files (all three proven on this machine), so every triple has a dav1d flavour.

### Added
- **`checkFFmpegRecipes`, and `-Pkiteffmpeg.ffmpeg.autoBake=true`.** A vendored FFmpeg tree is a dead artifact: nothing rebuilt it and nothing compared it, so a recipe change in `buildSrc` and the `.a` files on disk drifted apart in silence. Measured: `av1_videotoolbox` was pinned into the Apple hwaccel list on 2026-08-19 and every Apple tree still lacked it a day later with no check red anywhere. Every bake already stamped its exact configure line into the tree; nobody read it. `checkFFmpegRecipes` now reads it and names the capability flags that moved, with the task to re-run. `-Pkiteffmpeg.ffmpeg.autoBake=true` is the automatic half: the compile tasks depend on the bake, so Gradle re-bakes exactly when its inputs moved and skips it as UP-TO-DATE when they did not. Opt-in, because a first bake is tens of minutes. Machine-specific flags (`--prefix`, `--cc`, SDK paths) are excluded so an Xcode update never reads as drift, and the dav1d toggle is excluded because the plugin's dav1d contract already guards it in both directions.

- **`kiteffmpegCleanCache` and `kiteffmpeg { cleanCacheOnClean = true }`.** `clean` wipes `build/`, but nothing ever wiped what the plugin GRABBED: downloaded FFmpeg archives live in the shared Gradle cache (`<gradle-user-home>/caches/kiteffmpeg`) and outlived every project clean invisibly. The task is the visible handle; the property hooks it into `clean` for consumers who want a cleared project to mean cleared provisioning too. Default off, because the cache is shared by every project on the machine. `ffmpeg.localRoot` is never touched either way: the plugin only reads that tree and must not delete what it did not create.
- **`kiteffmpegInfo`.** Prints one line per wired Kotlin/Native target: source, license, version, dav1d, libass, and where the binaries come from (the download URL or the resolved lib directory). The provisioning decisions all happen across lazy providers at configuration time, which made them invisible; this makes them a sentence instead of a link-failure autopsy.

- **A real JVM variant, so a desktop app is one dependency line.** `jvmMain` compiled
  `unsupportedMain` until now, so every JVM consumer got a library whose every entry point threw,
  while the working JNI implementation was compiled only for Android. The jvm target builds the
  real tree now, its test source set runs the shared codec-contract suite (41 tests green over real
  FFmpeg), and the host JNI library rides inside the jar under `kiteffmpeg-native/<os>-<arch>/`,
  self-contained: the libraries the link pulls from a package manager travel with it, their load
  commands rewritten to `@loader_path` and each one re-signed, because Apple silicon refuses an
  invalidated signature with SIGKILL and no exception. `JniLibrary` tries an explicit
  `kiteffmpeg.jni.path`, then `java.library.path`, then the bundle. The JVM API dump gains
  `MediaByteSource` and the `MediaSource.open` overload that takes one, which the placeholder never
  had.
- **FFmpeg for Linux and Windows.** `buildFFmpegForLinuxX64`, `...LinuxArm64` and `...MingwX64`
  produce real trees for the first time, cross-built from the Kotlin/Native toolchains so the ABI
  matches what Kotlin/Native links against, at a reduced profile (software codecs plus zlib, no
  third-party encoder or text stack). Measured: 109 native tests pass on linuxArm64 in a container
  over the result, and the whole stack links to a PE32+ binary for Windows.
  `-Pkiteffmpeg.withDesktopTargets=true` adds the three triples to a publication instead of
  replacing its Apple and Android variants.
- **Owned stream colour and typed VP9 metadata.** Video stream snapshots now carry container/probe
  colour declarations plus typed VP9 profile, level, bit depth and chroma subsampling across both
  native and JNI builders. Nine compatible C accessors advance the C ABI from 2.5 to 2.6, the
  export set from 186 to 195 names, and the signature baseline from 201 to 210 records.
- **Owned codec-configuration snapshots for hardware decoders.** `StreamInfo.codecExtradata`
  carries a copy of records such as avcC and hvcC across both the native and JNI boundaries,
  without exposing an FFmpeg pointer. The bounded C copy helper rejects invalid sizes and advances
  the compatible C ABI from 2.4 to 2.5, the export set from 185 to 186 names, and the signature
  baseline from 200 to 201 records.
- **VideoToolbox hardware decode behind the opaque boundary.**
  Every Apple FFmpeg build (macOS, iOS device, iOS simulator) now enables the `h264_videotoolbox`
  and `hevc_videotoolbox` HWACCELs, and two C funnels carry them: `ffkmp_codecctx_use_videotoolbox`
  attaches the device context between allocation and open and installs a format negotiation that
  falls back to software when the hardware withdraws mid-stream, and `ffkmp_frame_hw_download`
  copies a hardware frame's pixels and presentation properties back to an ordinary frame. On
  Kotlin: `MediaSource.openDecoder(..., hardware = HardwareAccel.VideoToolbox)` and
  `Frame.downloadFromHardware()`, capability-honest on every platform (a build without the
  framework refuses typed at open). The JNI bridge carries both rows, so macOS JVM decodes
  through VideoToolbox too, proven by a differential contract arm that runs identically on the
  cinterop and JNI boundaries. C ABI minor 3 to 4; export names 183 to 185; signature records
  198 to 200. iOS links gain the CoreMedia/CoreVideo/VideoToolbox frameworks.
- **JVM and Android actuals over a dynamically registered JNI bridge.** The common decode,
  playback, frame, filter, sink, remux and transcode contracts now have JVM/Android
  implementations over generation-tagged opaque handles. The bridge validates the full FFmpeg
  identity before attaching the VM, maps native failures into the public typed error hierarchy,
  copies Java arrays at the boundary, and invalidates borrowed descendants when their parent
  closes. A test-only macOS arm64 dylib drives JVM contract and registration tests. The local
  Android target is `minSdk 24` and feeds `arm64-v8a` plus `x86_64` JNI libraries into the AAR
  model with 16 KiB ELF alignment and packaging-model checks. This is source and host/build
  evidence only: no jar or AAR is public, no Android playback or UI surface is claimed, and
  MediaCodec selection is only through FFmpeg named decoders such as `h264_mediacodec`.
- **A local-only mobile Apple substrate.** On an arm64 Mac, `-Pkiteffmpeg.applePhoneTargetsOnly=true` registers exactly macosArm64, iosArm64 and iosSimulatorArm64, is mutually exclusive with the standing target selectors, is accepted only by `publishToMavenLocal` and is refused by remote publication before repository work. The iOS FFmpeg tasks use the shared STANDARD software-playback set, `--disable-autodetect`, SDK zlib and SDK cross flags, with no desktop third-party stack, GPL build or hardware encode. (VideoToolbox DECODE was added to every Apple target later, by the hardware decode entry below; encode remains desktop-only.) `BuildFFmpegTask`, repository path resolution, the Apple-phone selector and Local-plugin validation refuse their iOS GPL cases before tree lookup with the stable diagnostic `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.` `FFmpegSource.Local` consumes a complete `<localRoot>/<license>/<target>/{include,lib}` tree without network access, validates all six archives and headers for every wired target, links iOS with exactly zlib and puts the local macOS search path before its host fallback. Nothing was publicly published or released.
- **The FFmpeg helper layer is real C now, with its own build, tests, sanitizer runs and fuzz targets.** It used to be 949 lines of text inside `kiteffmpeg/src/nativeInterop/cinterop/ffmpeg.def`, which had no translation unit and therefore no object file, no test, no sanitizer run and no coverage; 19 of the 176 helpers were never called from Kotlin at all. The extraction produced `native/kitecodec-c/`: nine translation units, one per subsystem, compiled per Kotlin/Native target into a static archive that cinterop embeds, with `KC_API` on the exported helpers and `-fvisibility=hidden` on everything else. The generator and `scripts/verify-lift.sh` proved that historical move byte for byte and were then retired; these are ordinary maintained sources now. The opaque migration below subsequently changed the def and Kotlin call sites without changing the public Kotlin API.
- **The compatible half of the opaque C surface, ABI 1.1.** `kitecodec_handles.h` adds the eleven forward-declared aliases `kc_codec`, `kc_codec_ctx`, `kc_codec_par`, `kc_dict`, `kc_dict_entry`, `kc_filter_ctx`, `kc_filter_graph`, `kc_fmt_ctx`, `kc_frame`, `kc_packet` and `kc_stream`, with no FFmpeg include. The helper surface adds the seven wrappers `ffkmp_codecctx_send_packet`, `ffkmp_codecctx_receive_frame`, `ffkmp_codecctx_send_frame`, `ffkmp_codecctx_receive_packet`, `ffkmp_find_encoder_by_name`, `ffkmp_find_decoder_by_name` and `ffkmp_filter_exists`, plus the five accessors `ffkmp_media_type_video`, `ffkmp_media_type_audio`, `ffkmp_media_type_subtitle`, `ffkmp_media_type_data` and `ffkmp_media_type_attachment`. Those twelve functions were compatible additions: the export set moved from 163 to 175, comprising 169 `ffkmp_` and six `kc_` symbols, and the C ABI moved from 1.0 to 1.1. They remained dormant from Kotlin until the ABI 2.0 migration adopted them.
- **Breaking C and cinterop change: the opaque boundary is complete at ABI 2.0.** The 140 original helper declarations that named FFmpeg types now use the eleven `kc_*` aliases, `kitecodec_helpers.h` no longer supplies FFmpeg typedefs or layouts transitively, and the cinterop def parses only the helper, handle and ABI headers. Raw libav functions, constants and struct layouts disappear from the klib; eleven incomplete forward tags remain behind the aliases and Kotlin source is forbidden to name them directly. Native consumers must use the `kc_*` handles and `ffkmp_*` functions; six Kotlin implementation files migrate to those names while the public Kotlin API remains byte-for-byte unchanged. The export set stays at 175, the new 189-record signature ratchet holds declaration shape, and nothing has been publicly published.
- **Required C arguments now fail with `AVERROR(EINVAL)` instead of reaching FFmpeg as invalid pointers.** The sixteen guarded entry points are `ffkmp_frame_get_buffer`, `ffkmp_codecpar_from_context`, `ffkmp_codecpar_copy_for_mux`, `ffkmp_fmt_open_input`, `ffkmp_fmt_find_stream_info`, `ffkmp_fmt_read_frame`, `ffkmp_fmt_alloc_output2`, `ffkmp_fmt_write_frame`, `ffkmp_codecctx_open`, `ffkmp_codecctx_from_par`, `ffkmp_graph_build_video`, `ffkmp_graph_build_audio`, `ffkmp_graph_build_video_multi`, `ffkmp_graph_build_audio_multi`, `ffkmp_graph_send` and `ffkmp_graph_receive`. Six nullable controls preserve the intentional FFmpeg meanings: a NULL audio-filter description selects `anull`; a NULL graph-send frame signals EOF; a NULL mux packet flushes; a NULL output-format name permits inference; a NULL codec lets `ffkmp_codecctx_open` use the codec remembered by its context; and a NULL path is valid for `ffkmp_fmt_alloc_output2` when a nonempty format is supplied.
- **New public types for the identity gate.** `FFmpegIdentity` and `FFmpegLibraryIdentity` carry the whole report as Kotlin values (per-library header and runtime version triples, verdicts, both licence strings, the provisioning sentence, whether the bypass was used); `FFmpegError.IncompatibleFFmpegRuntime` is the typed failure a rejection throws, with the identity attached; and `Versions` gains per-library header/runtime accessors (`avutilHeader` and siblings). All are in the committed API dump.
- **An FFmpeg header versus runtime identity gate, called before anything allocates.** In the direction that matters, older headers against a newer runtime, every symbol resolves and the link succeeds while measured field offsets are wrong and 48 of the helpers read or write through one of them; reconnaissance reproduced wrong values read and then a SIGSEGV inside `av_frame_free`. A generated unit inside the same C compilation freezes the six `LIB*_VERSION_INT` macros, and `kc_init` compares them to the six `*_version()` functions under `pthread_once`. Policy: major must be equal, runtime minor at or above header minor, micro reported and never fatal, plus a cross-library `*_configuration()` agreement check that catches a mixed install. The verdict carries a report with both licence strings, the provisioning sentence and the runtime configuration, and KitePlayer surfaces a rejection as an ordinary typed playback error rather than a crash. `KITECODEC_FFMPEG_ABI_BYPASS=1`, and only that exact value, downgrades a rejection to a warning printed once per process for diagnosis; the report records that it was used.
- **`ffmpeg.version` in the Gradle plugin DSL is validated.** A consumer writing `version = "n7.1"` with the default prebuilt source used to download FFmpeg 7.1 archives and link them against a klib whose stubs were compiled against n8.0 headers, which links cleanly and corrupts at runtime. Configuration now fails with a sentence naming both refs and the two ways out. One build-time assertion also holds the `n8.0` expectation in `BuildFFmpegTask`, the plugin and `publish.yml` to the same value, and to the vendored checkout when it is present.
- **A committed klib ABI baseline and coupling ratchets.** `kiteffmpeg/api/kiteffmpeg.klib.api` exists and `apiCheck` runs in the macOS CI job, so an accidental public signature change now fails a build instead of shipping. `native/kitecodec-c/coupling-baseline.txt` plus `./gradlew checkCinteropCoupling` require zero direct FFmpeg imports, calls and named raw structs from Kotlin while reporting opaque `ffkmp_*` traffic separately. The C signature baseline independently holds all 189 public declaration records, so an alias retarget or parameter change cannot hide behind an unchanged symbol name.
- **A C test suite, three sanitizer variants and six fuzz targets.** Seven suites run 274 cases per variant and 822 across plain, ASan and TSan. They cover the 39 ownership helpers for exact allocation pairing under a Mach-O interposer, two argument guards, all 12 fixed buffer sites and the four size-taking copy helpers at their limit and one byte past it, the arithmetic helpers at their overflow vectors, `ffkmp_strerror`'s thread affinity, the per call `SwsContext` in `ffkmp_frame_convert_pixfmt`, one case per identity verdict against doctored header trees, and the 22 cases in `test_args`. Each of the six suites that preceded `test_args` was proved load bearing by mutating copies of the sources and requiring the failure. The six fuzz targets cover every C entry point that parses a caller's string; they run as a corpus replay over 103 committed textual seeds in every gate, and a Linux CI job is configured to build them as libFuzzer targets but has not run yet, so no coverage-guided search has happened so far.
- The low-level playback layer, behind the `@KiteFFmpegLowLevelApi` opt-in, built for and consumed by [KitePlayer](https://github.com/yuroyami/KitePlayer): `MediaSource.openPacketReader` (owned packets, transactional stream selection, `avformat_seek_file` with a real min/max window and flag set), `MediaSource.openDecoder` (one independent decoder per stream: `send`/`receive`/`flush`/`isDrained`; the first exposure of `avcodec_flush_buffers` anywhere in the binding), `Frame.withPlanes` (zero-copy plane pointers with row pitches; video frames only, audio rejects with a clear message) and `Frame.hardwareSurface`.
- Overflow-safe timestamp helpers on the low-level types: `Packet.ptsMicros`/`dtsMicros`/`durationMicros` and `Frame.ptsMicros`/`durationMicros`, all through the 128-bit `av_rescale_q`, null on `AV_NOPTS_VALUE`.
- Colour metadata on `FrameInfo` (`ColorInfo`: matrix, primaries, transfer, range, chroma siting, with the conventional SD/HD guess applied at frame height), plus frame duration, keyframe flag, sample aspect ratio and `isHardware`.
- On `StreamInfo`: `Disposition` flags, `rotationDegrees` from the display matrix, per-stream start times, and `channelLayoutMask` (also on audio `FrameInfo`), the native channel order mask so 5.1 side and 5.1 back are distinguishable.
- `MediaSource.isSeekable`, read from the real I/O context instead of assumed.
- `MediaSource.startTimeMicros`: where a container's timeline begins. Raw stream/frame timestamps are absolute and include it; every parameter KiteFFmpeg takes (`seekMicros`, `extractFrame`, trim bounds) is content-relative. Exposed so callers can convert between the two.
- Vendored FFmpeg profile now also builds `mpeg4` (encode + decode), `flac`, and the `pcm_s16le`/`s24le`/`f32le` encoders, the dependency-free baseline every profile shares. Previously the LGPL build had **no** video encoder except libsvtav1 and mjpeg, and the already-enabled `wav`/`flac` muxers had no encoder to feed them.
- Vendored FFmpeg profile gained the MPEG-TS muxer/demuxer, the `matroska_audio` muxer (`.mka`), and the `http`/`tcp` protocols. (`https` still needs a TLS backend and is not built; see the note in `BuildFFmpegTask`.)
- CI job `vendored-lgpl`: builds the shipped LGPL profile from source and runs the unit tests, native tests and full e2e against **that**, not against Homebrew's FFmpeg.
- Full single-pass `demux → decode → filter → encode → mux` pipeline for video and audio (`Transcoder.transcode`): frame-exact trim, `videoCopy`/`audioCopy`/`subtitleCopy` stream copy, container metadata, typed progress (`TranscodeProgress`).
- `Frame.ofVideo` / `Frame.ofAudio`: build frames from raw bytes for generative pipelines (images-to-video, synthesized audio).
- `MediaSink.open(path, format, options)`: explicit container selection and muxer private options (`movflags=+faststart`).
- Semantic error hierarchy: `FFmpegError` now classifies `AVERROR_*` codes into `FileNotFound`, `PermissionDenied`, `InvalidData`, `EncoderNotFound`, `DecoderNotFound`, `MuxerNotFound`, `FilterNotFound`, and more (raw code retained; unmapped codes fall back to `AvError`).
- `Rational`: `Comparable`, `plus`/`minus`/`div`/`unaryMinus`, overflow-safe construction and scalar multiply.
- `StreamInfo.metadata` (per-stream tags, `language`, `title`, …), 10-bit pixel format constants (`yuv420p10le`, `p010le`, …), `s64`/`s64p` sample formats.
- Explicit API mode + `@Throws` annotations across the public surface; kotlinx binary-compatibility-validator wired (klib mode).
- Maven publishing (vanniktech plugin, Central Portal, signing, Dokka javadoc jar) for `kiteffmpeg`; Gradle Plugin Portal metadata + a TestKit functional test for `kiteffmpeg-gradle-plugin`.

### Changed
- **BREAKING: the `ffmpeg.dav1d` toggle is now a contract enforced in BOTH directions.** Before, `if (archive.exists()) linkerOpts("-ldav1d")` meant the tree decided and the toggle only validated one way: a consumer whose tree carried dav1d linked it without one line of their build saying so, and `dav1d = false` silently linked it anyway. dav1d is compiled into `libavcodec` when FFmpeg itself is built, so a link-time toggle can neither add nor subtract it; what it now does is refuse a mismatch loudly at task realisation, with the one-line fix in the message. Consumers whose Local tree carries dav1d must state `ffmpeg { dav1d = true }` from this release on.

- **Every public entry point can now refuse to start.** `kc_init` runs first inside 15 C entry points, so a mismatched FFmpeg runtime produces a typed error naming what disagreed instead of undefined behaviour later. Nothing else about the failure behaviour of the Kotlin API changed, and micro version differences never reject.
- **15 helper symbols were deleted, and the `archived/` directory with them.** The 15 were exported surface that nothing imported; in a versioned library that is a compatibility promise nobody meant to make. `scripts/check-deleted-surface.sh` proves neither repository refers to any of them. Safe because nothing has ever been published from here and there are no tags. The six def files under `nativeInterop/cinterop/archived/` were referenced by no build file and duplicated 176 helper names, which made every later grep report false hits.
- The vendored FFmpeg build and the plugin now agree on their expected FFmpeg ref by assertion rather than by a comment asking three files to be kept in sync.
- **Breaking (behaviour):** `MediaSource.seekMicros`, `extractFrame`, and `Transcoder`/`Remuxer` trim bounds are now consistently **content-relative**. They previously mixed the two conventions, `extractFrame` compensated for a container's start time, `Transcoder`/`Remuxer` did not, so trimming an MPEG-TS capture silently shifted the window by the container's start (~1.4s, and more with an offset).
- `MediaSink.addVideoEncoder` now converts frames whose pixel format differs from the encoder's instead of failing. An unfiltered transcode of a 10-bit source, or a filter chain without a trailing `format=`, used to die with a bare `EINVAL`. Frame *dimensions* still throw, a size mismatch is a config error, and silently rescaling would hide it.
- `MediaSink.close()` now drains every encoder before writing the trailer, as its documentation always claimed. A sink closed without an explicit `finish()` used to discard whatever the encoder still had buffered.
- `AudioEncoder.sampleRate` / `.channels` now report what the encoder actually opened with rather than what was requested.
- The sample and `scripts/e2e.sh` pick their video encoder by probing the linked FFmpeg instead of hard-coding `libx264` (GPL-only). `kiteffmpeg-sample info` prints the choice for scripts to read.
- **Breaking:** `FFmpegError` no longer extends `RuntimeException`. It is a plain sealed hierarchy carried by `FFmpegException` (the only thrown type).
- **Breaking (contract):** frames emitted by `decodedFrames`/`decodeStreams`/`FilterGraph.process` are now OWNED by the collector, safe to buffer (`toList()`, `buffer()`), and each must be closed. Callback-style outputs (`feedInput`) keep the callback-scope rule.
- `Rational.Zero.inverse` and division by zero now throw instead of constructing an invalid rational.
- Concurrent misuse of one `MediaSource` (second decode flow, seek/close mid-decode) is rejected with `IllegalStateException` instead of racing native code.

### Security
- **The JNI identity report accumulated into a fixed buffer with no bound at all.** `kj_abi.c` used `off += snprintf(buf + off, sizeof buf - (size_t)off, ...)`, and `snprintf` returns the length it WOULD have written: once `off` passed the buffer size, `buf + off` pointed outside the array and `sizeof buf - (size_t)off` wrapped to an enormous `size_t`, so the next append wrote past the end with a length that disabled every bound the call had. Seven of the report's fields are strings of unbounded length; the reachable maximum today is about 2.3 KB against 4 KB, so this was latent rather than live. `kj_append.h` now refuses rather than truncates, because the Kotlin side splits the report into a fixed 31 fields and a short one parses into wrong values instead of failing. It is the same guard `helpers_filter.c` already applied by hand, in one place, and it carries no `jni.h` so a host suite can compile the shipped arithmetic: `test_append` is the eighth C suite, and it caught a real bug in the fix while it was being written, since `vsnprintf` writes the part that fits before the overflow is detectable.
- **A filter value reached the graph unescaped.** `AudioFormat.compile()` interpolated `sample_fmts=$it` raw, one line above a neighbour that routed its value through `escapeFilterValue`, so `AudioFormat(sampleFormat = "fltp,volume=0")` silently appended an entire extra filter to the graph. Now escaped like every other typed value. The compiled output for ordinary values is unchanged, because `escapeFilterValue` quotes only values carrying a structural character.

### Fixed
- **Apple builds never compiled the AV1 hardware decode path.** `--enable-hwaccel` named only `h264_videotoolbox` and `hevc_videotoolbox`, so `av1_videotoolbox` was absent from every macOS and iOS tree. It is pinned now, and the two configure goldens moved with it. **This alone does not give you hardware AV1, and it is not claimed to:** an hwaccel attaches to a decoder, `libdav1d` is an external decoder that carries none, and `avcodec_find_decoder(AV_CODEC_ID_AV1)` returns `libdav1d` ahead of FFmpeg's native `av1` on every build that has both. Reaching VideoToolbox needs a decoder chosen by name plus a software fallback policy, which this library does not have yet. Pinning the hwaccel is the half that can be done without that work, and it is the half that has to exist first. Untested on AV1 silicon: the proving machine is an M2, which has none.
- **cinterop embedded a stale helper archive during incremental development.** The cinterop task uses its own up-to-date check, over the def and the headers, and it does not track a library the def merely names. So editing only a `.c` body rebuilt the archive and left the previous one inside the klib, with the configuration cache on or off. The archive is now declared an input of the cinterop task. Measured both ways: before the fix the embedded archive stayed at one digest while the built one moved, and after it the same edit re-executes cinterop and the object inside the klib disassembles to the new body. A missing archive always failed loudly, so this only ever affected a local edit, which is what most changes are.
- **A wrong-architecture archive could be embedded silently.** A Linux ELF archive placed where the macOS arm64 one belongs was embedded without complaint and failed at the consumer's final link with `archive member not a mach-o file`. The compile task's output directory is keyed by the konan target name and never shared, and the task asserts the produced object's architecture before archiving.
- The def declared `linkerOpts` for macOS, Linux, mingw and Android but not for iOS, although three iOS targets are registered, so the six `-l` flags never reached an iOS link. Added. The local mobile Apple proof now exercises the arm64 device and simulator trees; no CI or public-artifact result is inferred.
- **A stack buffer overflow reachable from public filter descriptions.** Both audio filter-graph builders accumulated `snprintf` results into a fixed buffer and checked the total only after every append; on truncation `snprintf` returns the length it wanted, so a long description moved the write pointer past the stack array with a wrapped remaining size. Every append is now bounds-checked before the next pointer is computed, and a length test drives descriptions from 0 bytes to 1 MB through both builders.
- `openPacketReader` left unselected streams on `AVDISCARD_ALL` after the reader closed, so a later batch decode of those streams silently returned nothing. Discard flags are restored when the reader closes and on the open failure path.
- `FilterGraph` multi-input feeding could spin forever when the graph needed a different input pad (EAGAIN with no progress now throws a typed error naming the starved graph), and `drainTo` did not release its landing frame when a callback declined to; it now always does.
- `MediaSink` stepped missing and non-monotonic audio timestamps by the current frame's sample count instead of the previous frame's, so frames of 960 then 1024 samples started at 0 and 1024 rather than 0 and 960.
- `seekMicros` while a `PacketReader` owns the demuxer cursor now throws instead of moving the cursor out from under it.
- Reading any getter of a closed `Packet`, or sending one to a decoder, dereferenced freed native memory and returned plausible garbage; both now throw.
- **The vendored static path had never linked.** `ffmpeg.def` names only the six libav* libraries, which is all a shared FFmpeg needs; a static `libavcodec.a` also needs every third-party archive it draws symbols from named at the final link. The library's own build never did that, so `native-libs/` was unusable, `_svt_av1_enc_init`, `_png_set_tRNS_to_alpha`, `_gr_*`, and the CoreGraphics/CoreText/VideoToolbox frameworks all went unresolved. `BuildFFmpegTask` now bundles those archives into the tree and `StaticLinkFlags` names them.
- **The vendored profile enabled no bitstream filters at all.** libavformat inserts these itself during stream copy, so their absence produced a corrupt output file rather than an error, copying h264 from MPEG-TS into mp4 yielded a file ffprobe rejects with "No start code is found". `extract_extradata`, `aac_adtstoasc`, `h264/hevc_mp4toannexb`, `vp9_superframe` are now built.
- **`eq` and `boxblur` are `deps="gpl"` in FFmpeg**, so the LGPL profile silently dropped them, including `eq`, the filter every example and the e2e suite reached for. They moved to the GPL flavour; the examples now use `hue=b=…`, which exists everywhere.
- `.m4a` and `.mka` map to FFmpeg's separate `ipod` and `matroska_audio` muxers. Neither was enabled, so writing either extension failed with "Unable to choose an output format".
- Decoding no longer aborts the whole file on a recoverable error. Every seek into a stream carrying its parameter sets in band (MPEG-TS) lands before the next SPS/PPS, so the first packets after it decode to nothing; that used to throw instead of being skipped, which made trimming a broadcast capture impossible.
- Seeking before a decode now aims deliberately early (`seekForDecode`). Indexless containers resolve a seek by searching byte positions and can land past the keyframe they aimed at, after which the decoder emits nothing until the next IDR, a whole GOP of requested content silently dropped.
- `BuildFFmpegTask` verified nothing after `make install`. A prefix GNU make cannot parse (any path containing `#`) truncates `libdir` to empty, so the install becomes a silent no-op that still exits 0, and `FFmpegPaths` then falls back to the system FFmpeg, exactly the "publication silently drops a target" failure the publish guard exists to prevent. It now copies source to a unique hash-free temporary tree, excluding `.git` and every `build` subtree while preserving executability, and runs configure, make and install only there. It normalises the first `ffbuild/config.log` line into the installed `lib/kiteffmpeg/ffmpeg-configure.txt`, requires that single-line provenance record while verifying the six archives and headers in scratch and in a Java/NIO sibling staging copy, and only then replaces the final tree. Packaging reads that installed record alone and refuses missing, blank, multiline or obsolete unavailable evidence. Failure retains scratch for diagnosis and never replaces the last good output.
- `kiteffmpeg-sample` ignored `-Pkiteffmpeg.ffmpeg.license`, always resolving the LGPL tree even when the library it links was built against the GPL one.
- **The vendored macOS FFmpeg build never configured.** It died on `ERROR: libmp3lame >= 3.98.3 not found` even with the dependency installed: `BuildFFmpegTask` passed no `--extra-cflags`/`--extra-ldflags` for the Homebrew prefix, and lame ships no pkg-config file. It now passes both, plus `PKG_CONFIG_PATH`.
- **`--enable-videotoolbox` never produced a VideoToolbox encoder.** Under `--disable-everything` each encoder must be named explicitly; `h264_videotoolbox`/`hevc_videotoolbox` were not, so vendored Apple builds advertised hardware encode and had none. (The Android profile always listed `h264_mediacodec` correctly.)
- Trim windows on containers that do not start at zero (MPEG-TS) selected the wrong range, see the seek/trim change above.
- `StreamInfo.codec` reported the *decoder's* name, so an AV1 stream read as `libdav1d`, Opus as `libopus`, and any stream with no decoder compiled in as `codec_<number>`. It now reports the codec's canonical name.
- `Frame.encodeImage()` overwrote the source frame's timestamp with 0 when no pixel-format conversion was needed (it encodes the caller's own frame in that path).
- `Transcoder` produced **no output file at all**, and no error, when the trim window selected nothing: the muxer header is written lazily on the first packet. It is now written eagerly, as `Remuxer` already did.
- Non-monotonic audio timestamps were bumped by one tick. With a codec time-base of `1/sample_rate` that collapses a 1024-sample AAC frame into a single sample; the bump is now the frame's own duration.
- Encoder packets were rescaled from the time-base requested in the spec rather than the one `avcodec_open2` settled on, which the muxer stream was already (correctly) told about.
- Output timestamps could go negative: streams share one rebase origin, so a stream starting before the claiming one (AAC priming samples) fell below zero. The muxer's `avoid_negative_ts` policy is now pinned to `make_zero`, which shifts every stream by the same amount and keeps the A/V offset intact.
- `FFmpegPaths` (and the Gradle plugin's system-FFmpeg lookup) matched library paths by existence, never by architecture, so `macosX64` on an Apple-silicon Mac resolved to the arm64 Homebrew libraries and `linuxArm64` on an x64 host to the x86_64 ones. System resolution is now restricted to the host's own target, with an error that says why.
- `FetchFFmpegTask` wrote a shared Gradle-user-home cache with delete-then-move and no locking, so concurrent builds could pull the tree out from under a running link. It now holds an exclusive lock for the whole fetch and marks completion with a file written last.
- `FetchFFmpegTask` threw a `NullPointerException` on a relative redirect `Location` instead of resolving it against the request URL.
- Muxer crash on header-write failure: a failed `avformat_write_header` no longer leads `close()` into `av_write_trailer` on a headerless context.
- A/V desync after trims: all streams of one sink now rebase timestamps against a single shared origin instead of each stream's own first timestamp.
- `Remuxer.remux` and copy-only transcodes are now cancellable (cancellation checked every packet).
- Filter graphs: `EAGAIN` from `av_buffersrc_add_frame` retries the same frame instead of silently dropping it; feeding a closed graph throws instead of use-after-free.
- Trim end detection gates on dts (monotonic) instead of pts, B-frame reordering no longer stops the demux a GOP early; graph-buffered frames past the trim end are filtered at the encoder.
- `extractFrame` accounts for nonzero container start times (MPEG-TS).
- `Rational.inverse` normalises its result (no more negative denominators).
- Released FFmpeg zips now bundle LGPL/GPL license texts, a BUILD-INFO provenance record, and the source URL (LGPL compliance); release assets are attested and checksummed.

### Baseline surface

Everything below grew from `0.0.1` and is listed for orientation rather than as a change.

- `MediaSource`: probing (`streams`, `metadata`, `durationMicros`), `decodedFrames`/`decodeStreams` frame flows (EAGAIN-correct, single demux pass for multiple streams), `seekMicros`, `extractFrame` + `Frame.encodeImage` thumbnails.
- `MediaSink`: `addVideoEncoder`/`addAudioEncoder` (shared EAGAIN-correct encode core, monotonic zero-based pts, per-encoder `options`), `addCopyStream`, `setMetadata`.
- `FilterGraph`: single- and multi-input video/audio graphs (overlay, amix), encoder-ready audio output, `setOutputFrameSize` for AAC's 1024-sample framing.
- `Remuxer.remux`: lossless container rewrite with keyframe-snapped trim.
- Capability probing (`FFmpeg.versions`, `hasEncoder`/`hasDecoder`/`hasFilter`, `buildConfiguration`).
- Hardware encode via `h264_videotoolbox` (verified on macOS arm64; `allow_sw` for VMs). Android exposes FFmpeg MediaCodec names, but this changelog does not turn them into an Android playback or encoder qualification.
- FFmpeg build tasks (`buildFFmpegFor<Target>[Gpl]`): vendored static FFmpeg cross-compile, LGPL by default with a GPL opt-in flavour, Android NDK MediaCodec profile.
- `kiteffmpeg-gradle-plugin`: provisions prebuilt/system FFmpeg for consumer builds with SHA-256 verification (in-repo; not yet published).
- Documentation site (MkDocs Material) and CI (macOS / Ubuntu / Windows unit + e2e, plus a vendored-LGPL job that exercises the shipped profile).

### Known gaps
- Remote publishing has produced no artifact; Maven Central still needs a real release run with Central Portal credentials and signing in CI. The KLIB and JVM API dumps are committed and `apiCheck` guards them locally.
- No public JVM runtime jar or Android AAR yet; the JNI/AAR source and packaging proof is local,
  and there is no Android playback, physical-device, Compose or Android View qualification.
- No custom AVIO (in-memory/pipe sources and sinks), no chapter read/write, no subtitle decode (copy only). Channel layouts expose the native order mask, but named/custom layout objects, `extended_data` access and more than 8 channels remain absent.
- No hardware decode / hwframes pipelines; no bitstream filters on the copy path (MPEG-TS Annex B unsupported).
- iOS targets lack CI verification.

## [0.0.1] - 2026

Initial development baseline: project structure, consolidated FFmpeg cinterop binding (`ffmpeg.def` + `ffkmp_*` helpers), and the first working decode/encode paths on macOS arm64. Everything listed under [Unreleased] grew from here; treat 0.0.1 as the "it exists and transcodes" milestone rather than a supported release.

[Unreleased]: https://github.com/yuroyami/KiteFFmpeg/compare/kiteffmpeg-v0.4.0...HEAD
[0.4.0]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/kiteffmpeg-v0.4.0
[0.3.0]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/kiteffmpeg-v0.3.0
[0.2.0]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/kiteffmpeg-v0.2.0
[0.1.0]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/kiteffmpeg-v0.1.0
[0.0.1]: https://github.com/yuroyami/KiteFFmpeg/releases/tag/v0.0.1
