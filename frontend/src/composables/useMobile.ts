import { ref, onMounted, onUnmounted } from 'vue'

// 手机/窄屏判断，用于表格与卡片列表切换、弹窗宽度等。
export function useMobile(breakpoint = 820) {
  const isMobile = ref(typeof window !== 'undefined' ? window.innerWidth <= breakpoint : false)
  const handler = () => {
    isMobile.value = window.innerWidth <= breakpoint
  }
  onMounted(() => window.addEventListener('resize', handler))
  onUnmounted(() => window.removeEventListener('resize', handler))
  return isMobile
}
