import ChatWindow from '@/components/ChatWindow'

export default function Home() {
  return (
    <main style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '2rem' }}>
      <h1 style={{ color: '#1a1a2e', marginBottom: '0.5rem' }}>🎓 ExamPrep AI</h1>
      <p style={{ color: '#555', marginBottom: '2rem' }}>
        Learn deeply. Prepare confidently. — Powered by PageIndex RAG
      </p>
      <ChatWindow />
    </main>
  )
}
