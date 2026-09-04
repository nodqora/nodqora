import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // ADR-0053's three GETs, served by the Spring Boot app next door.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    globals: true,
    // The tests are over pure logic — layering, traversal, phrasing — and read the golden documents
    // straight off disk, so they need Node rather than a DOM.
    environment: 'node',
  },
})
