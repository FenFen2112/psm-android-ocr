import subprocess
import time
import os
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler
import google.genai as genai
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

PROJECT_PATH = r"D:\PSM\android-one-tap-phase-a"
API_KEY = os.getenv("GEMINI_API_KEY")
if not API_KEY:
    print("❌ Error: GEMINI_API_KEY not found in .env file")

IMPORTANT_FILES = [
    "XianyuParser.kt",
    "OcrFacade.kt",
    "BubbleOverlayService.kt",
    "CaptureActivity.kt",
    "ScreenCaptureHelper.kt",
    "GasClient.kt",
    "QueueRetryWorker.kt",
    "MainActivity.kt"
]

last_run_time = 0

# Initialize Gemini Client
client = genai.Client(api_key=API_KEY)
MODEL_ID = "gemini-2.0-flash" # Optimized for speed/cost

class ChangeHandler(FileSystemEventHandler):

    def on_modified(self, event):
        global last_run_time
        current_time = time.time()

        # Chống spam save (debounce)
        if current_time - last_run_time < 5:
            return

        if event.is_directory:
            return

        # Chỉ watch file quan trọng
        if not any(name in event.src_path for name in IMPORTANT_FILES):
            return

        last_run_time = current_time
        
        print("\n" + "="*40)
        print(f"File Changed: {os.path.basename(event.src_path)}")
        print("="*40)

        try:
            # Chuyển absolute path sang relative path cho git
            rel_path = os.path.relpath(event.src_path, PROJECT_PATH)
            print(f"--- Debug: Git diff for relative path: {rel_path} ---")
            
            # Lấy diff của riêng file bị thay đổi
            diff = subprocess.check_output(
                ["git", "diff", rel_path],
                cwd=PROJECT_PATH,
                text=True,
                encoding="utf-8"
            )

            if not diff.strip():
                # Thử check staged diff nếu unstaged trống
                diff = subprocess.check_output(
                    ["git", "diff", "--cached", event.src_path],
                    cwd=PROJECT_PATH,
                    text=True,
                    encoding="utf-8"
                )

            if not diff.strip() or len(diff.strip()) < 20:
                print("No significant diff detected.")
                return

            print(f"Diff detected ({len(diff)} chars). Requesting AI Review...")

            prompt = f"""
You are guarding the architecture of an Android OCR automation platform.
Review this git diff for regressions, memory leaks, or architectural smells.

Main priorities:
- OCR reliability & Parser stability
- MediaProjection & Bitmap memory safety
- Android lifecycle correctness
- Unsafe coroutine usage

Review this git diff:

{diff[:15000]}
"""

            response = client.models.generate_content(
                model=MODEL_ID,
                contents=prompt
            )

            review = response.text
            print("\n=== AI REVIEW ===\n")
            print(review)

            # Quick alerts
            alerts = {
                "memory leak": "⚠ MEMORY LEAK RISK",
                "lifecycle": "⚠ LIFECYCLE WARNING",
                "bitmap": "⚠ BITMAP WARNING",
                "regression": "⚠ REGRESSION RISK"
            }
            
            lower_review = review.lower()
            for trigger, msg in alerts.items():
                if trigger in lower_review:
                    print(f"\n{msg}")

            # Save latest results
            with open("latest_diff.txt", "w", encoding="utf-8") as f:
                f.write(diff)
            with open("latest_review.txt", "w", encoding="utf-8") as f:
                f.write(review)
            with open("review_history.txt", "a", encoding="utf-8") as f:
                f.write(f"\n\n[{time.ctime()}] File: {event.src_path}\n{'-'*20}\n{review}")

        except Exception as e:
            print(f"\nError during review: {e}")

if __name__ == "__main__":
    observer = Observer()
    observer.schedule(ChangeHandler(), PROJECT_PATH, recursive=True)
    observer.start()

    print(f"\nWatching Android project: {PROJECT_PATH}")
    print(f"AI Model: {MODEL_ID}")
    print("Press Ctrl+C to stop.")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        observer.stop()
    
    observer.join()
