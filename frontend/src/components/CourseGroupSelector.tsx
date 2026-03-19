'use client'

interface CourseItem {
  courseId: string
  branch:   string | null
  subject:  string | null
  title:    string | null
}

interface Props {
  courses:  CourseItem[]
  value:    string
  onChange: (id: string) => void
}

const BRANCH_ORDER = ['CSE', 'ECE', 'EEE', 'CIVIL', 'DA-AIML', 'OTHER']

export default function CourseGroupSelector({ courses, value, onChange }: Props) {
  const groups: Record<string, CourseItem[]> = {}
  for (const c of courses) {
    const branch = c.branch ?? 'OTHER'
    if (!groups[branch]) groups[branch] = []
    groups[branch].push(c)
  }

  const orderedBranches = [
    ...BRANCH_ORDER.filter(b => groups[b]),
    ...Object.keys(groups).filter(b => !BRANCH_ORDER.includes(b)),
  ]

  return (
    <select
      value={value}
      onChange={e => onChange(e.target.value)}
      style={{ padding: '4px 8px', borderRadius: 6, border: 'none', fontSize: 14, minWidth: 200, background: '#fff' }}
    >
      {courses.length === 0 && <option value="">Loading…</option>}
      {orderedBranches.map(branch => (
        <optgroup key={branch} label={branch}>
          {groups[branch].map(c => (
            <option key={c.courseId} value={c.courseId}>
              {c.title ?? c.courseId}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  )
}

