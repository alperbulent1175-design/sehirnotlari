import SwiftUI
import WebKit

@main
struct SehirNotlariApp: App {
    var body: some Scene {
        WindowGroup { NotesView() }
    }
}

struct NotesView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> NotesController {
        NotesController()
    }

    func updateUIViewController(_ controller: NotesController, context: Context) {}
}

final class NotesController: UIViewController, WKNavigationDelegate, WKScriptMessageHandler {
    private var webView: WKWebView!
    private lazy var assets = Bundle.main.bundleURL.appendingPathComponent("assets", isDirectory: true)

    override func loadView() {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(WKUserScript(
            source: "window.Android = { aiAvailable: () => false };",
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--ui-test-import-fixture") {
            configuration.userContentController.addUserScript(WKUserScript(
                source: """
                const input = document.getElementById('restoreFile');
                input.addEventListener('click', event => {
                    event.preventDefault();
                    const file = new File(
                        ['{"Ankara":[{"id":20261120,"title":"iOS yedek denemesi","content":"Android JSON","date":"20.11.2026"}]}'],
                        'android-yedek.json',
                        {type: 'application/json'}
                    );
                    const files = new DataTransfer();
                    files.items.add(file);
                    input.files = files.files;
                    input.dispatchEvent(new Event('change', {bubbles: true}));
                });
                """,
                injectionTime: .atDocumentEnd,
                forMainFrameOnly: true
            ))
        }
        #endif
        configuration.userContentController.add(ExportHandler(owner: self), name: "exportNotes")
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        view = webView
        webView.loadFileURL(assets.appendingPathComponent("index.html"), allowingReadAccessTo: assets)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor action: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let url = action.request.url
        let isLocal = url?.isFileURL == true && url?.standardizedFileURL.path == assets.appendingPathComponent("index.html").standardizedFileURL.path
        decisionHandler(isLocal ? .allow : .cancel)
        if action.navigationType == .linkActivated, let url, url.scheme == "https" {
            UIApplication.shared.open(url)
        }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame,
              message.frameInfo.request.url?.standardizedFileURL.path == assets.appendingPathComponent("index.html").standardizedFileURL.path,
              let json = message.body as? String,
              presentedViewController == nil else { return }

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let file = directory.appendingPathComponent("sehir-notlari.json")
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data(json.utf8).write(to: file, options: .atomic)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            let alert = UIAlertController(title: "Dışa aktarma başarısız", message: "JSON dosyası oluşturulamadı.", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Tamam", style: .default))
            present(alert, animated: true)
            return
        }

        let share = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = view
        share.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        share.completionWithItemsHandler = { _, _, _, _ in
            try? FileManager.default.removeItem(at: directory)
        }
        present(share, animated: true)
    }
}

private final class ExportHandler: NSObject, WKScriptMessageHandler {
    weak var owner: NotesController?

    init(owner: NotesController) { self.owner = owner }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        owner?.userContentController(controller, didReceive: message)
    }
}
