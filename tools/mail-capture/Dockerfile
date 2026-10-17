FROM python:3.13-alpine
WORKDIR /app
COPY server.py /app/server.py
USER 10001:10001
EXPOSE 1025 1080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:1080/health',timeout=2)"
CMD ["python", "-u", "server.py"]
