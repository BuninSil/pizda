#!/usr/bin/env bash
# Руки — установщик агента на ноут (antiX/Debian). Идемпотентный, можно гонять повторно.
#
# Запуск одной строкой (токен — с главного экрана приложения):
#   curl -fsSL https://raw.githubusercontent.com/BuninSil/pizda/main/laptop/install.sh | bash -s -- <ТОКЕН>
#
# Если конфиг ~/.config/ruki-laptop.json уже есть — токен можно не передавать.
set -e

TOKEN="${1:-}"
WORKER="${RUKI_WORKER:-https://raspy-dew-5339.vladislav20050705.workers.dev}"
RAW="https://raw.githubusercontent.com/BuninSil/pizda/main/laptop/ruki-laptop.py"

echo "== apt-пакеты =="
sudo apt install -y python3-websocket xdotool scrot xclip wmctrl imagemagick \
  x11-xserver-utils tesseract-ocr tesseract-ocr-rus alsa-utils bluez \
  libnotify-bin feh xbacklight pm-utils curl zip unzip xdg-utils || \
  echo "!! apt ругнулся — если 404 на зеркало, см. laptop/README.md шаг 0, потом перезапусти"

mkdir -p ~/.local/bin ~/.config ~/.cache ~/.desktop-session

echo "== агент =="
curl -fsSL "$RAW" -o ~/.local/bin/ruki-laptop.py
chmod +x ~/.local/bin/ruki-laptop.py

echo "== конфиг =="
if [ -f ~/.config/ruki-laptop.json ]; then
  echo "уже есть ~/.config/ruki-laptop.json — не трогаю"
else
  if [ -z "$TOKEN" ]; then
    echo "!! нет токена. Запусти так: ... | bash -s -- <ТОКЕН> (токен с главного экрана приложения)"
    exit 1
  fi
  cat > ~/.config/ruki-laptop.json <<JSON
{ "url": "$WORKER", "token": "$TOKEN", "secret": "" }
JSON
  echo "создан ~/.config/ruki-laptop.json"
fi

echo "== sudo без пароля =="
echo "$USER ALL=(ALL) NOPASSWD: ALL" | sudo tee /etc/sudoers.d/ruki >/dev/null && sudo chmod 440 /etc/sudoers.d/ruki

echo "== автозапуск =="
grep -q ruki-laptop ~/.desktop-session/startup 2>/dev/null || \
  echo 'python3 ~/.local/bin/ruki-laptop.py >> ~/.cache/ruki-laptop.log 2>&1 &' >> ~/.desktop-session/startup

echo "== (пере)запуск агента =="
pkill -f ruki-laptop.py 2>/dev/null || true
sleep 1
nohup python3 ~/.local/bin/ruki-laptop.py >> ~/.cache/ruki-laptop.log 2>&1 &
sleep 2
echo "---- лог ----"
tail -n 8 ~/.cache/ruki-laptop.log
echo "-------------"
echo "Готово. Если видишь 'connected' — агент на связи."
