import { useHostTheme } from "cursor/canvas";

const sections = [
  [
    "Signal path",
    "Waveshare L76K → UART → Pico 2 W → BLE → tracking app → local trip database. The map and statistics read the same saved points."
  ],
  [
    "Recording and gaps",
    "Each connected Android phone records its own copy using a foreground service, including when the screen is locked. After a lost fix or radio link, show the last valid position with its age and start a new segment. Unattended recording would require storage on the Pico and a way to replay missed samples."
  ],
  [
    "Offline maps",
    "Install a Moravskoslezský kraj PMTiles pack, local style, fonts and icons before departure. Outside the pack, keep recording and show coordinates. Test Czech labels, urban streets and rural trails."
  ],
  [
    "Trip statistics",
    "Show elapsed time, accepted distance, current speed and average moving speed. Exclude gaps, duplicate points and stationary drift from distance."
  ],
  [
    "Future server",
    "An optional uploader can send committed trips in batches using stable sample IDs. A failed upload leaves recording and trip history intact."
  ]
];
const stages = [
  [
    "1. Prove the receiver outdoors",
    "Capture ten minutes of UART traffic and decode one timestamped point per epoch. Replay it on a development machine. Corrupt a checksum, remove GGA and inject a no-fix sentence. The decoder must reject corruption, keep altitude optional and stop publishing a live position during loss of fix."
  ],
  [
    "2. Prove the radio with two subscribers",
    "Stream the same trace to two authorized clients for 30 minutes at 1 Hz. Compare sequence numbers and coordinates. Disconnect the first client, reconnect it, then reboot the Pico. The other client must keep receiving throughout the first client's disconnect. Both must recognize the new session after reboot. Record any packet losses and the highest verified subscriber count."
  ],
  [
    "3. Save a trip with the screen locked",
    "Start a trip on both Android phones, lock both screens and carry them together for 30 minutes. Unlock, stop and reopen each trip. Compare their sequence coverage against the run with the screens on. Verify point counts, timestamps and segments. Repeat with Bluetooth toggled, app termination, denied permissions and low storage. Distinguish recording, interrupted and saved states. Simulate duplicate samples and verify unchanged distance."
  ],
  [
    "4. Take a fully offline field trip",
    "Disable Wi-Fi and cellular data, enable Bluetooth and start from a clean app installation with a local map pack. Acquire an outdoor fix, watch the marker move, pan, recenter, leave coverage and return. Stop and reopen the trip after restarting the device. Verify local labels, visible attribution, retained history and no required network access."
  ],
  [
    "5. Calibrate and set release targets",
    "Use a stationary outdoor test and a route of known length to measure false distance, distance error, fix latency, BLE gaps and battery runtime. Record receiver, antenna placement, client OS, map region and test conditions. Pick release tolerances from the intended activity. Fix the worst observed failure before expanding map coverage or adding sync."
  ]
];
export default function TrackerPlan() {
  const theme = useHostTheme();
  function details(items: string[][]) {
    return items.map(([title, body]) => (
      <details key={title} style={{borderBottom: "1px solid " + theme.stroke.secondary, padding: "9px 0"}}>
        <summary style={{cursor: "pointer", fontWeight: 600}}>{title}</summary>
        <p>{body}</p>
      </details>
    ));
  }
  return <main style={{maxWidth: 720, margin: "0 auto", padding: 24, lineHeight: 1.55, color: theme.text.primary}}>
    <h1 style={{fontSize: 24}}>Offline GPS tracker</h1><p>Record a trip from an external GPS receiver, follow it on an offline map and reopen it after restarting the app.</p>
    <h2 style={{fontSize: 18}}>System design</h2>{details(sections)}
    <h2 style={{fontSize: 18}}>Build order and acceptance tests</h2>{details(stages)}
  </main>;
}
