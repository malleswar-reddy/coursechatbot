'use client'

import { useState, useEffect, useRef } from 'react'

const FOCUS_SECONDS  = 25 * 60
const BREAK_SECONDS  = 5  * 60

export default function PomodoroTimer() {
  const [secondsLeft, setSecondsLeft]   = useState(FOCUS_SECONDS)
  const [isBreak,     setIsBreak]       = useState(false)
  const [running,     setRunning]       = useState(false)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    if (running) {
      intervalRef.current = setInterval(() => {
        setSecondsLeft(s => {
          if (s <= 1) {
            setIsBreak(prev => {
              setSecondsLeft(!prev ? BREAK_SECONDS : FOCUS_SECONDS)
              return !prev
            })
            return 0
          }
          return s - 1
        })
      }, 1000)
    }
    return () => { if (intervalRef.current) clearInterval(intervalRef.current) }
  }, [running])

  const mm = String(Math.floor(secondsLeft / 60)).padStart(2, '0')
  const ss = String(secondsLeft % 60).padStart(2, '0')
  const pct = isBreak
    ? ((BREAK_SECONDS - secondsLeft) / BREAK_SECONDS) * 100
    : ((FOCUS_SECONDS - secondsLeft) / FOCUS_SECONDS) * 100

  return (
    <div style={{ ...styles.bar, background: isBreak ? '#dcfce7' : '#eff6ff' }}>
      <span style={{ fontSize: 14 }}>{isBreak ? '☕ Break' : '🍅 Focus'}</span>
      <div style={styles.track}>
        <div style={{ ...styles.fill, width: `${pct}%`, background: isBreak ? '#22c55e' : '#3b82f6' }} />
      </div>
      <span style={styles.time}>{mm}:{ss}</span>
      <button onClick={() => setRunning(r => !r)} style={styles.btn}>
        {running ? '⏸' : '▶'}
      </button>
      <button onClick={() => { setRunning(false); setIsBreak(false); setSecondsLeft(FOCUS_SECONDS) }} style={styles.btn}>↺</button>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  bar:  { display: 'flex', alignItems: 'center', gap: 8, padding: '6px 14px', borderBottom: '1px solid #eee', fontSize: 13 },
  track:{ flex: 1, height: 6, borderRadius: 3, background: '#e5e7eb', overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3, transition: 'width 0.5s linear' },
  time: { fontVariantNumeric: 'tabular-nums', fontWeight: 700, minWidth: 44 },
  btn:  { background: 'none', border: 'none', cursor: 'pointer', fontSize: 16, padding: '0 4px' },
}

