// Watches the Mac clipboard for pictures, for dex-on-mac.sh.
//   osascript -l JavaScript mac-clipboard.js OUT_DIR [PASTEBOARD_NAME]
// Each time a picture is copied (a screenshot, "Copy Image", or an image file copied in Finder)
// it saves it to OUT_DIR and prints one line: PICTURE_FILE <tab> MIME <tab> TEXT_FILE.
// TEXT_FILE holds the Mac's plain text exactly as it is (often empty); the phone needs it so
// scrcpy's own Ctrl+V clipboard sync doesn't replace the picture. Plain text copies print nothing:
// scrcpy already handles text. PASTEBOARD_NAME is only for testing on a private pasteboard.
ObjC.import('AppKit');

const PNG = 4;  // NSBitmapImageFileTypePNG
const AS_IS = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };
const CONVERT = { heic: 1, heif: 1, tif: 1, tiff: 1, bmp: 1 };

function say(line) {
  $.NSFileHandle.fileHandleWithStandardOutput.writeData($(line + '\n').dataUsingEncoding($.NSUTF8StringEncoding));
}

function toPng(data) {
  if (!data || data.isNil()) return null;
  const rep = $.NSBitmapImageRep.imageRepWithData(data);
  if (rep.isNil()) return null;
  const png = rep.representationUsingTypeProperties(PNG, $({}));
  return png.isNil() ? null : png;
}

function str(pb, type) {
  const s = pb.stringForType(type);
  return s.isNil() ? null : s.js;
}

function grab(pb, dir, n) {
  const text = str(pb, $.NSPasteboardTypeString) || '';
  let data = null, mime = 'image/png';

  const fileUrl = str(pb, 'public.file-url');
  if (fileUrl) {
    const path = $.NSURL.URLWithString(fileUrl).path.js;
    const ext = path.split('.').pop().toLowerCase();
    if (AS_IS[ext]) {
      data = $.NSData.dataWithContentsOfFile(path);
      mime = AS_IS[ext];
    } else if (CONVERT[ext]) {
      data = toPng($.NSData.dataWithContentsOfFile(path));
    } else {
      return null;  // not a picture: drop the file on the DeX window instead
    }
  } else {
    if (text !== '') return null;  // text, maybe with a picture (Pages, Notes): scrcpy pastes the text
    const png = pb.dataForType('public.png');
    const jpeg = pb.dataForType('public.jpeg');
    if (!png.isNil()) data = png;
    else if (!jpeg.isNil()) { data = jpeg; mime = 'image/jpeg'; }
    else data = toPng(pb.dataForType('public.tiff'));
  }
  if (!data || data.isNil() || data.length === 0) return null;

  const ext = mime === 'image/jpeg' ? 'jpg' : mime.split('/')[1];
  const picFile = dir + '/' + n + '.' + ext;
  const textFile = dir + '/' + n + '.txt';
  if (!data.writeToFileAtomically(picFile, true)) return null;
  $(text).writeToFileAtomicallyEncodingError(textFile, true, $.NSUTF8StringEncoding, null);
  return picFile + '\t' + mime + '\t' + textFile;
}

function run(argv) {
  const dir = argv[0];
  const pb = argv[1] ? $.NSPasteboard.pasteboardWithName(argv[1]) : $.NSPasteboard.generalPasteboard;
  let last = pb.changeCount;
  let n = 0;
  for (;;) {
    delay(0.4);
    const count = pb.changeCount;
    if (count === last) continue;
    last = count;
    try {
      const line = grab(pb, dir, ++n);
      if (line) say(line);
    } catch (e) {
      console.log('clipboard watcher: ' + e);
    }
  }
}
