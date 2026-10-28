# Руки — агент для ноута (второй девайс)

`ruki-laptop.py` — Python-агент, который даёт Claude полный контроль над ноутом
через тот же релей и коннектор, что и телефон. Исходящий WSS на
`wss://<worker>/<token>/__laptop`, никаких входящих портов. 79 тулз с префиксом
`laptop_` (мышь, клава, экран, окна, дисплеи, звук, сеть, BT, файлы, процессы,
пакеты, питание…). Под слабое железо: только Python 3 + утилиты из apt, без Node.

Защита — на уровне релея: без правильного `x-update-secret` агент не подключится,
без правильного токена `/mcp` отвечает 404. На ноуте один админ с sudo без пароля,
агент выполняет команды сразу, без подтверждений.

---

## Установка на ноуте (antiX) — по шагам

### 0. (если зеркало apt протухло — 404 на `apt update`)

Определи кодовое имя релиза и перекинь Debian-репы на CDN-зеркало:

```bash
CN=$(. /etc/os-release 2>/dev/null; echo ${VERSION_CODENAME:-$(cat /etc/debian_version)})
# antiX: debian-репы лежат в /etc/apt/sources.list.d/*.list — перекидываем на deb.debian.org
sudo sed -ri 's#https?://[^ ]*(deb\.debian\.org|ftp\.[a-z]+\.debian\.org|mirror[^ ]*debian)[^ ]*#http://deb.debian.org/debian#g' \
  /etc/apt/sources.list /etc/apt/sources.list.d/*.list 2>/dev/null
sudo apt update
```

Если релиз старый/EOL (buster и древнее) и всё равно 404 — замени `deb.debian.org/debian`
на `archive.debian.org/debian` в той же команде и добавь `-o Acquire::Check-Valid-Until=false`
к `apt update`. (Всё это умеет и сам агент тулзой `laptop_apt_sources` — но сначала надо
его поставить.)

### 1. Пакеты — одной строкой

```bash
sudo apt install -y python3-websocket xdotool scrot xclip wmctrl imagemagick \
  x11-xserver-utils tesseract-ocr tesseract-ocr-rus alsa-utils bluez \
  libnotify-bin feh xbacklight pm-utils curl zip unzip xdg-utils
```

Необязательные (поставь, если нужны соответствующие тулзы):
- `bluez-alsa-utils` (или `bluealsa`) — звук в блютуз-колонку (`laptop_audio_output target=bt`);
- `connman connman-ui` или `network-manager` (`nmcli`) — если Wi-Fi-тулзы не находят бэкенд;
- если нет пакета `python3-websocket` — `pip3 install --user websocket-client`.

Любая тулза, у которой не хватает утилиты, не падает, а пишет, какой пакет поставить.

### 2. Положить скрипт

```bash
mkdir -p ~/.local/bin ~/.config ~/.cache
curl -fsSL https://raw.githubusercontent.com/BuninSil/pizda/main/laptop/ruki-laptop.py \
  -o ~/.local/bin/ruki-laptop.py
chmod +x ~/.local/bin/ruki-laptop.py
```

### 3. Конфиг

В приложении на телефоне нажми **«Скопировать конфиг для ноута»** и вставь в файл:

```bash
nano ~/.config/ruki-laptop.json
```

Содержимое (то, что скопировал телефон — URL, токен, секрет):

```json
{ "url": "https://raspy-dew-5339.vladislav20050705.workers.dev", "token": "<твой-токен>", "secret": "" }
```

(`secret` оставь пустым, если на воркере не задан `UPDATE_SECRET`.)

### 4. sudo без пароля (один раз)

```bash
echo "$USER ALL=(ALL) NOPASSWD: ALL" | sudo tee /etc/sudoers.d/ruki && sudo chmod 440 /etc/sudoers.d/ruki
```

### 5. Автозапуск (antiX, без systemd) + watchdog

Агент сам перезапускается после обрыва связи и падения (внутренний цикл с бэкоффом).
Чтобы поднимался после логина в X — добавь строку в `~/.desktop-session/startup`:

```bash
mkdir -p ~/.desktop-session
echo 'python3 ~/.local/bin/ruki-laptop.py >> ~/.cache/ruki-laptop.log 2>&1 &' >> ~/.desktop-session/startup
```

### 6. Запустить сейчас (не дожидаясь перелогина)

```bash
nohup python3 ~/.local/bin/ruki-laptop.py >> ~/.cache/ruki-laptop.log 2>&1 &
```

---

## Проверка, что подключилось

```bash
# на ноуте — в логе должно появиться "connected":
tail -n 5 ~/.cache/ruki-laptop.log

# откуда угодно — статус релея (laptop.connected должно стать true):
curl -s https://raspy-dew-5339.vladislav20050705.workers.dev/<твой-токен>/__status
```

В Claude после этого `tools/list` покажет и телефонные тулзы, и `laptop_*`.
Тумблер «Ноут» в приложении на телефоне включает/выключает выдачу `laptop_`-тулз;
при выключенном или при оффлайн-ноуте они просто не отдаются.

## Обновить агент

```bash
pkill -f ruki-laptop.py
curl -fsSL https://raw.githubusercontent.com/BuninSil/pizda/main/laptop/ruki-laptop.py -o ~/.local/bin/ruki-laptop.py
nohup python3 ~/.local/bin/ruki-laptop.py >> ~/.cache/ruki-laptop.log 2>&1 &
```
