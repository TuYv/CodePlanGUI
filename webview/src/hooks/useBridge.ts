import { useEffect } from 'react'

interface BridgeCallbacks {
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
}

export function useBridge(callbacks: BridgeCallbacks) {
  useEffect(() => {
    const setup = () => {
      if (!window.__bridge) {
        window.__bridge = {
          sendMessage: () => {},
          newChat: () => {},
          onStart: callbacks.onStart,
          onToken: callbacks.onToken,
          onEnd: callbacks.onEnd,
          onError: callbacks.onError,
        }
      } else {
        window.__bridge.onStart = callbacks.onStart
        window.__bridge.onToken = callbacks.onToken
        window.__bridge.onEnd = callbacks.onEnd
        window.__bridge.onError = callbacks.onError
      }
    }

    setup()
    document.addEventListener('bridge_ready', setup)
    return () => document.removeEventListener('bridge_ready', setup)
  }, [callbacks.onStart, callbacks.onToken, callbacks.onEnd, callbacks.onError])
}
