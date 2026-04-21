import { useEffect, useRef, useState } from 'react'

type EventHandler = (type: string, payload: any) => void

export function useBridge(onEvent: EventHandler): boolean {
  const [bridgeReady, setBridgeReady] = useState(() => window.__bridge?.isReady === true)
  const frontendReadySentRef = useRef(false)
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  useEffect(() => {
    const setup = () => {
      if (!window.__bridge) {
        window.__bridge = {
          isReady: false,
          sendMessage: () => {},
          newChat: () => {},
          openSettings: () => {},
          cancelStream: () => {},
          frontendReady: () => {},
          debugLog: () => {},
          onEvent: (_type: string, _payloadJson: string) => {},
          approvalResponse: () => {},
        } as any
      }
      window.__bridge.onEvent = (type: string, payloadJson: string) => {
        try {
          const payload = JSON.parse(payloadJson)
          onEventRef.current(type, payload)
        } catch (e) {
          console.warn(`[CodePlanGUI] Failed to parse event payload: type=${type}`, e)
        }
      }

      const isReady = window.__bridge.isReady === true
      setBridgeReady(isReady)
      if (isReady && !frontendReadySentRef.current) {
        frontendReadySentRef.current = true
        window.__bridge.frontendReady()
      }
    }

    setup()
    document.addEventListener('bridge_ready', setup)
    return () => document.removeEventListener('bridge_ready', setup)
  }, [])

  return bridgeReady
}
