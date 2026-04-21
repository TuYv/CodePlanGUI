import { useEffect, useRef } from 'react'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'

const marked = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code, lang) {
      const language = hljs.getLanguage(lang) ? lang : 'plaintext'
      return hljs.highlight(code, { language }).value
    },
  }),
)

async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Fall through to the legacy copy path.
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()

  try {
    return document.execCommand('copy')
  } finally {
    document.body.removeChild(textarea)
  }
}

interface AssistantMarkdownProps {
  content: string
}

export function AssistantMarkdown({ content }: AssistantMarkdownProps) {
  const htmlRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!htmlRef.current) return

    const raw = marked.parse(content) as string
    htmlRef.current.innerHTML = DOMPurify.sanitize(raw)

    htmlRef.current.querySelectorAll('pre').forEach((pre) => {
      const code = pre.querySelector('code')
      if (!code || pre.querySelector('button')) return

      pre.classList.add('assistant-code-block')
      const mount = document.createElement('div')
      mount.className = 'bubble-copy-anchor'
      pre.appendChild(mount)

      const root = document.createElement('div')
      mount.appendChild(root)
      const button = document.createElement('button')
      button.className = 'bubble-copy-fallback'
      button.textContent = 'Copy'
      button.onclick = async () => {
        const success = await copyText(code.textContent || '')
        if (!success) return
        button.textContent = 'Copied'
        window.setTimeout(() => {
          button.textContent = 'Copy'
        }, 2000)
      }
      root.appendChild(button)
    })
  }, [content])

  return <div ref={htmlRef} className="assistant-markdown" />
}
