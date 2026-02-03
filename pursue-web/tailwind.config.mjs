/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{astro,html,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        'pursue-blue': {
          50: '#E3F2FD',
          100: '#BBDEFB',
          500: '#1976D2',
          600: '#1565C0',
          700: '#0D47A1',
        },
        'pursue-gold': {
          50: '#FFF8E1',
          100: '#FFECB3',
          500: '#F57C00',
          600: '#E65100',
        },
        'pursue-gray': {
          50: '#FAFAFA',
          100: '#F5F5F5',
          200: '#EEEEEE',
          500: '#9E9E9E',
          700: '#616161',
          900: '#212121',
        },
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          'sans-serif',
        ],
      },
    },
  },
  plugins: [],
};
