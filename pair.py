import subprocess
import time

import os
adb_path = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
pair_address = "100.125.110.103:33639"
pairing_code = "333804"

print(f"ADB path: {adb_path}")
print(f"File exists: {os.path.exists(adb_path)}")

# Start the pairing process
proc = subprocess.Popen(
    [adb_path, "pair", pair_address],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1
)

# Wait a moment for the prompt
time.sleep(1)

# Send the pairing code
proc.stdin.write(pairing_code + "\n")
proc.stdin.flush()

# Wait for completion
output, _ = proc.communicate(timeout=10)
print(output)

if proc.returncode == 0:
    print("\n✓ Pairing successful!")
    # Try to connect
    time.sleep(2)
    result = subprocess.run(
        [adb_path, "connect", "100.125.110.103:39267"],
        capture_output=True,
        text=True
    )
    print(result.stdout)
    print(result.stderr)
else:
    print(f"\n✗ Pairing failed with code {proc.returncode}")
