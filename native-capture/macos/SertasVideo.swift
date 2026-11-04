import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo

// Нативный захват экрана (видео) для sertasram через ScreenCaptureKit. Pull-модель:
// SCStream держит ПОСЛЕДНИЙ NV12-кадр; Java тянет через nativeRead и конвертит
// NV12 -> I420 на своей стороне (webrtc-java VideoBufferConverter). Высокий FPS,
// zero-copy IOSurface на стороне ОС. Никаких апколлов Swift -> JVM.

/// Хранит один последний кадр (старый перезаписывается — для видео важен свежий).
final class VideoRing {
    private let lock = NSLock()
    private var buf = [UInt8]()
    private var w = 0
    private var h = 0
    private var seq = 0
    private var lastRead = 0

    func put(_ bytes: [UInt8], _ width: Int, _ height: Int) {
        lock.lock(); defer { lock.unlock() }
        buf = bytes; w = width; h = height; seq += 1
    }

    /// Скопировать последний кадр в out, если он новее прочитанного.
    /// Возврат: (скопировано байт, width, height); (0,_,_) — нового кадра нет.
    func take(_ out: UnsafeMutablePointer<UInt8>, _ maxBytes: Int) -> (Int, Int, Int) {
        lock.lock(); defer { lock.unlock() }
        if seq == lastRead || buf.isEmpty { return (0, 0, 0) }
        let n = min(maxBytes, buf.count)
        buf.withUnsafeBufferPointer { out.update(from: $0.baseAddress!, count: n) }
        lastRead = seq
        return (n, w, h)
    }
}

final class VideoCapturer: NSObject, SCStreamOutput, SCStreamDelegate {
    let ring = VideoRing()
    private var stream: SCStream?
    private let queue = DispatchQueue(label: "dev.sertas.video.capture")
    private let reqW: Int
    private let reqH: Int
    private let fps: Int

    private var patternThread: Thread?

    init(width: Int, height: Int, fps: Int) {
        self.reqW = width
        self.reqH = height
        self.fps = fps
    }

    /// Тест-паттерн (env SERTAS_VIDEO_TESTPATTERN=1): движущийся градиент в NV12 без
    /// SCStream — для headless-проверки pull-пути без разрешения Screen Recording.
    func startTestPattern() {
        let w = reqW - reqW % 2
        let h = reqH - reqH % 2
        let t = Thread { [weak self] in
            var f = 0
            let ySize = w * h
            let uvSize = w * (h / 2)
            while let self = self, !Thread.current.isCancelled {
                var out = [UInt8](repeating: 128, count: ySize + uvSize) // UV=128 (нейтраль)
                out.withUnsafeMutableBufferPointer { p in
                    let d = p.baseAddress!
                    for row in 0..<h {
                        for col in 0..<w {
                            d[row * w + col] = UInt8((col + f * 4) & 0xFF)
                        }
                    }
                }
                self.ring.put(out, w, h)
                f += 1
                Thread.sleep(forTimeInterval: 1.0 / 30.0)
            }
        }
        patternThread = t
        t.start()
    }

    func start() -> Bool {
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        SCShareableContent.getWithCompletionHandler { [weak self] content, _ in
            defer { sem.signal() }
            guard let self = self, let display = content?.displays.first else { return }
            let filter = SCContentFilter(display: display, excludingWindows: [])
            let cfg = SCStreamConfiguration()
            // Выходной размер по аспекту дисплея, ограниченный пресетом (без апскейла);
            // чётные размеры обязательны для NV12 (4:2:0).
            let scale = min(Double(self.reqW) / Double(display.width),
                            Double(self.reqH) / Double(display.height), 1.0)
            var outW = Int((Double(display.width) * scale).rounded())
            var outH = Int((Double(display.height) * scale).rounded())
            outW -= outW % 2
            outH -= outH % 2
            cfg.width = outW
            cfg.height = outH
            cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(self.fps))
            cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange // '420v' (NV12)
            cfg.queueDepth = 6
            cfg.showsCursor = true
            let s = SCStream(filter: filter, configuration: cfg, delegate: self)
            do {
                try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: self.queue)
                self.stream = s
                let st = DispatchSemaphore(value: 0)
                s.startCapture { err in ok = (err == nil); st.signal() }
                _ = st.wait(timeout: .now() + 4) // не виснуть, если колбэк не пришёл
            } catch {
                ok = false
            }
        }
        // Таймаут: без разрешения Screen Recording колбэк не приходит — иначе зависание.
        if sem.wait(timeout: .now() + 5) == .timedOut {
            return false
        }
        return ok
    }

    func stop() {
        patternThread?.cancel()
        patternThread = nil
        stream?.stopCapture { _ in }
        stream = nil
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferDataIsReady(sampleBuffer),
              let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb)
        let h = CVPixelBufferGetHeight(pb)
        guard CVPixelBufferGetPlaneCount(pb) >= 2,
              let yBase = CVPixelBufferGetBaseAddressOfPlane(pb, 0),
              let uvBase = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        let ySize = w * h
        let uvSize = w * (h / 2) // NV12: UV-плоскость = w байт * (h/2) строк
        var out = [UInt8](repeating: 0, count: ySize + uvSize)
        out.withUnsafeMutableBufferPointer { dst in
            let d = dst.baseAddress!
            let yp = yBase.assumingMemoryBound(to: UInt8.self)
            for row in 0..<h { memcpy(d + row * w, yp + row * yStride, w) }
            let uvp = uvBase.assumingMemoryBound(to: UInt8.self)
            for row in 0..<(h / 2) { memcpy(d + ySize + row * w, uvp + row * uvStride, w) }
        }
        ring.put(out, w, h)
    }
}

// MARK: - JNI (pull-модель)

private let gVLock = NSLock()
private var gVideo: VideoCapturer?

/// Запуск захвата экрана width x height @ fps. 1 — успех, 0 — нет дисплея/разрешения.
@_cdecl("Java_dev_sertas_engine_MacScreenVideoCapture_nativeStart")
public func Java_dev_sertas_engine_MacScreenVideoCapture_nativeStart(
    _ env: UnsafeMutableRawPointer?,
    _ clazz: UnsafeMutableRawPointer?,
    _ width: Int32,
    _ height: Int32,
    _ fps: Int32
) -> Int32 {
    gVLock.lock(); defer { gVLock.unlock() }
    if gVideo != nil { return 1 }
    let cap = VideoCapturer(width: Int(width), height: Int(height), fps: Int(fps))
    if ProcessInfo.processInfo.environment["SERTAS_VIDEO_TESTPATTERN"] == "1" {
        cap.startTestPattern()
        gVideo = cap
        return 1
    }
    if cap.start() {
        gVideo = cap
        return 1
    }
    return 0
}

/// Скопировать последний NV12-кадр в nv12 (если новый); dims[0]=width, dims[1]=height.
/// Возврат: число скопированных байт (0 — нового кадра нет).
@_cdecl("Java_dev_sertas_engine_MacScreenVideoCapture_nativeRead")
public func Java_dev_sertas_engine_MacScreenVideoCapture_nativeRead(
    _ env: UnsafeMutablePointer<JNIEnv?>?,
    _ clazz: jclass?,
    _ nv12: jbyteArray?,
    _ maxBytes: jint,
    _ dims: jintArray?
) -> jint {
    guard let env = env else { return 0 }
    gVLock.lock(); let cap = gVideo; gVLock.unlock()
    guard let cap = cap, maxBytes > 0 else { return 0 }

    let mx = Int(maxBytes)
    var tmp = [UInt8](repeating: 0, count: mx)
    var n = 0, w = 0, h = 0
    tmp.withUnsafeMutableBufferPointer { p in
        let r = cap.ring.take(p.baseAddress!, mx)
        n = r.0; w = r.1; h = r.2
    }
    if n == 0 { return 0 }

    guard let setByte = env.pointee?.pointee.SetByteArrayRegion,
          let setInt = env.pointee?.pointee.SetIntArrayRegion else { return 0 }
    tmp.withUnsafeBufferPointer { p in
        p.baseAddress!.withMemoryRebound(to: jbyte.self, capacity: n) { sp in
            setByte(env, nv12, 0, jint(n), sp)
        }
    }
    let wh: [jint] = [jint(w), jint(h)]
    wh.withUnsafeBufferPointer { setInt(env, dims, 0, 2, $0.baseAddress) }
    return jint(n)
}

@_cdecl("Java_dev_sertas_engine_MacScreenVideoCapture_nativeStop")
public func Java_dev_sertas_engine_MacScreenVideoCapture_nativeStop(
    _ env: UnsafeMutableRawPointer?,
    _ clazz: UnsafeMutableRawPointer?
) {
    gVLock.lock(); let cap = gVideo; gVideo = nil; gVLock.unlock()
    cap?.stop()
}
