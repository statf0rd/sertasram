import Foundation
import ScreenCaptureKit
import AVFoundation
import CoreMedia

// Нативный захват системного звука для sertasram (Фаза B). Pull-модель: SCStream
// пишет планарный Float32 в кольцевой буфер, Java тянет через nativeRead. Никаких
// апколлов Swift→JVM — только запись в буфер и копирование в Java-массивы по запросу.

/// Потокобезопасный буфер планарных L/R-сэмплов. Размер ограничен (старое
/// отбрасывается) — на случай, если Java читает медленнее, чем идёт захват.
final class AudioRing {
    private let lock = NSLock()
    private var left = [Float]()
    private var right = [Float]()
    private let cap = 48_000 * 2 // ~2с страховка

    init() {
        left.reserveCapacity(cap)
        right.reserveCapacity(cap)
    }

    func push(_ l: UnsafePointer<Float>, _ r: UnsafePointer<Float>, _ n: Int) {
        lock.lock(); defer { lock.unlock() }
        if left.count + n > cap {
            let drop = min(left.count, left.count + n - cap)
            left.removeFirst(drop)
            right.removeFirst(drop)
        }
        left.append(contentsOf: UnsafeBufferPointer(start: l, count: n))
        right.append(contentsOf: UnsafeBufferPointer(start: r, count: n))
    }

    /// Слить до maxFrames в out-массивы; вернуть число фреймов.
    func drain(_ outL: UnsafeMutablePointer<Float>, _ outR: UnsafeMutablePointer<Float>, _ maxFrames: Int) -> Int {
        lock.lock(); defer { lock.unlock() }
        let n = min(maxFrames, left.count)
        if n == 0 { return 0 }
        for i in 0..<n {
            outL[i] = left[i]
            outR[i] = right[i]
        }
        left.removeFirst(n)
        right.removeFirst(n)
        return n
    }
}

/// Захват: SCStream c capturesAudio, либо тест-тон (env SERTAS_AUDIO_TESTTONE=1)
/// для headless-проверки pull-пути без разрешения Screen Recording.
final class AudioCapturer: NSObject, SCStreamOutput, SCStreamDelegate {
    let ring = AudioRing()
    private var stream: SCStream?
    private let queue = DispatchQueue(label: "dev.sertas.audio.capture")
    private var toneThread: Thread?

    // MARK: тест-тон
    func startTestTone() {
        let t = Thread { [weak self] in
            var phase = 0
            while let self = self, !Thread.current.isCancelled {
                var l = [Float](repeating: 0, count: 480)
                var r = [Float](repeating: 0, count: 480)
                for i in 0..<480 {
                    let s = Float(0.25 * sin(2.0 * Double.pi * 440.0 * Double(phase) / 48_000.0))
                    phase += 1
                    l[i] = s
                    r[i] = s
                }
                l.withUnsafeBufferPointer { lp in
                    r.withUnsafeBufferPointer { rp in
                        self.ring.push(lp.baseAddress!, rp.baseAddress!, 480)
                    }
                }
                Thread.sleep(forTimeInterval: 0.01)
            }
        }
        toneThread = t
        t.start()
    }

    // MARK: ScreenCaptureKit
    func start() -> Bool {
        let sem = DispatchSemaphore(value: 0)
        var started = false
        SCShareableContent.getWithCompletionHandler { [weak self] content, _ in
            defer { sem.signal() }
            guard let self = self, let display = content?.displays.first else { return }
            let filter = SCContentFilter(display: display, excludingWindows: [])
            let cfg = SCStreamConfiguration()
            cfg.capturesAudio = true
            cfg.excludesCurrentProcessAudio = true
            cfg.sampleRate = 48_000
            cfg.channelCount = 2
            cfg.width = 2          // видео не используем, минимальный кадр
            cfg.height = 2
            cfg.minimumFrameInterval = CMTime(value: 1, timescale: 1)
            let s = SCStream(filter: filter, configuration: cfg, delegate: self)
            do {
                try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: self.queue)
                self.stream = s
                let startSem = DispatchSemaphore(value: 0)
                s.startCapture { err in
                    started = (err == nil)
                    startSem.signal()
                }
                _ = startSem.wait(timeout: .now() + 4) // не виснуть, если колбэк не пришёл
            } catch {
                started = false
            }
        }
        // Таймаут: без разрешения Screen Recording колбэк не приходит — иначе зависание.
        if sem.wait(timeout: .now() + 5) == .timedOut {
            return false
        }
        return started
    }

    func stop() {
        toneThread?.cancel()
        toneThread = nil
        stream?.stopCapture { _ in }
        stream = nil
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, CMSampleBufferDataIsReady(sampleBuffer) else { return }

        var sizeNeeded = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: &sizeNeeded,
            bufferListOut: nil,
            bufferListSize: 0,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: nil)
        guard sizeNeeded > 0 else { return }

        let ablRaw = UnsafeMutableRawPointer.allocate(byteCount: sizeNeeded, alignment: 16)
        defer { ablRaw.deallocate() }
        let ablPtr = ablRaw.assumingMemoryBound(to: AudioBufferList.self)

        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: ablPtr,
            bufferListSize: sizeNeeded,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer)
        guard status == noErr else { return }

        let list = UnsafeMutableAudioBufferListPointer(ablPtr)
        if list.count >= 2 {
            // Планарный: буфер 0 = L, буфер 1 = R.
            let lb = list[0]
            let rb = list[1]
            let n = Int(lb.mDataByteSize) / MemoryLayout<Float>.size
            if n > 0,
               let lp = lb.mData?.assumingMemoryBound(to: Float.self),
               let rp = rb.mData?.assumingMemoryBound(to: Float.self) {
                ring.push(lp, rp, n)
            }
        } else if list.count == 1 {
            // Interleaved L,R,L,R… — деинтерливим.
            let b = list[0]
            let frames = (Int(b.mDataByteSize) / MemoryLayout<Float>.size) / 2
            if frames > 0, let p = b.mData?.assumingMemoryBound(to: Float.self) {
                var l = [Float](repeating: 0, count: frames)
                var r = [Float](repeating: 0, count: frames)
                for i in 0..<frames {
                    l[i] = p[2 * i]
                    r[i] = p[2 * i + 1]
                }
                l.withUnsafeBufferPointer { lp in
                    r.withUnsafeBufferPointer { rp in
                        ring.push(lp.baseAddress!, rp.baseAddress!, frames)
                    }
                }
            }
        }
    }
}

// MARK: - JNI (pull-модель)

private let gLock = NSLock()
private var gCapturer: AudioCapturer?

/// Запуск захвата. Возврат 1 — успех, 0 — не удалось (нет дисплея/разрешения).
@_cdecl("Java_dev_sertas_engine_MacSystemAudioCapture_nativeStart")
public func Java_dev_sertas_engine_MacSystemAudioCapture_nativeStart(
    _ env: UnsafeMutableRawPointer?,
    _ clazz: UnsafeMutableRawPointer?
) -> Int32 {
    gLock.lock(); defer { gLock.unlock() }
    if gCapturer != nil { return 1 }
    let cap = AudioCapturer()
    if ProcessInfo.processInfo.environment["SERTAS_AUDIO_TESTTONE"] == "1" {
        cap.startTestTone()
        gCapturer = cap
        return 1
    }
    if cap.start() {
        gCapturer = cap
        return 1
    }
    return 0
}

/// Слить до maxFrames планарных сэмплов в left/right. Возврат — число фреймов (0 если пусто).
@_cdecl("Java_dev_sertas_engine_MacSystemAudioCapture_nativeRead")
public func Java_dev_sertas_engine_MacSystemAudioCapture_nativeRead(
    _ env: UnsafeMutablePointer<JNIEnv?>?,
    _ clazz: jclass?,
    _ leftArr: jfloatArray?,
    _ rightArr: jfloatArray?,
    _ maxFrames: jint
) -> jint {
    guard let env = env else { return 0 }
    gLock.lock(); let cap = gCapturer; gLock.unlock()
    guard let cap = cap, maxFrames > 0 else { return 0 }

    let mx = Int(maxFrames)
    var l = [Float](repeating: 0, count: mx)
    var r = [Float](repeating: 0, count: mx)
    let n = cap.ring.drain(&l, &r, mx)
    if n == 0 { return 0 }

    guard let setRegion = env.pointee?.pointee.SetFloatArrayRegion else { return 0 }
    l.withUnsafeBufferPointer { setRegion(env, leftArr, 0, jint(n), $0.baseAddress) }
    r.withUnsafeBufferPointer { setRegion(env, rightArr, 0, jint(n), $0.baseAddress) }
    return jint(n)
}

/// Остановить захват и освободить ресурсы.
@_cdecl("Java_dev_sertas_engine_MacSystemAudioCapture_nativeStop")
public func Java_dev_sertas_engine_MacSystemAudioCapture_nativeStop(
    _ env: UnsafeMutableRawPointer?,
    _ clazz: UnsafeMutableRawPointer?
) {
    gLock.lock(); let cap = gCapturer; gCapturer = nil; gLock.unlock()
    cap?.stop()
}
