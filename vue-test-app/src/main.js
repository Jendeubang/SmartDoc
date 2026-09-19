import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import './style.css'
import './styles/smartdoc-design-system.css'
import './styles/morandi-theme.css'
import './styles/morandi-sage-theme.css'
import { initializeSmartDocTheme } from './utils/theme'

initializeSmartDocTheme()

const app = createApp(App)

// Register Element Plus icons used by dynamically rendered navigation and tool cards.
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)
app.use(ElementPlus)
app.mount('#app')
