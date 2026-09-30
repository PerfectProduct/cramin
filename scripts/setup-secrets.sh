#!/usr/bin/env bash
# Заводит секреты разработки Cramin: .env (ключ OpenRouter, бюджет живых тестов)
# и, по желанию, релизный keystore вне репозитория + секреты GitHub Actions.
# Значения секретов никогда не печатаются. См. docs/RUNBOOK.md и SPEC §14.4, §12.3.
set -euo pipefail

REPO_SLUG="PerfectProduct/cramin"
KEY_ALIAS="cramin-release"

die() { echo "Ошибка: $*" >&2; exit 1; }

# --- Предусловия: корень репозитория и .env в .gitignore ---------------------
[ -f "SPEC.md" ] || [ -f "docs/SPEC.md" ] || [ -f "settings.gradle.kts" ] || die "запустите скрипт из корня репозитория cramin"
[ -d ".git" ] || die "здесь нет .git — запустите скрипт из корня репозитория"
git check-ignore -q .env || die ".env не в .gitignore — сначала исправьте .gitignore"

# --- Чтение текущих значений .env (только для вопроса «оставить?») -----------
# Разбор терпим к пробелам вокруг «=» и к кавычкам; значения не выводятся.
env_get() {
  local name="$1"
  [ -f .env ] || return 0
  sed -nE "s/^[[:space:]]*(export[[:space:]]+)?${name}[[:space:]]*=[[:space:]]*//p" .env \
    | head -n1 | tr -d '\r' | sed -E "s/[[:space:]]+\$//; s/^\"(.*)\"\$/\\1/; s/^'(.*)'\$/\\1/"
}

ask_keep() { # $1 — имя переменной; возвращает 0, если существующее значение оставляем
  local name="$1" answer
  read -rp "В .env уже есть ${name}. Оставить текущее значение? [Y/n] " answer
  case "${answer:-Y}" in [Nn]*) return 1 ;; *) return 0 ;; esac
}

OPENROUTER_API_KEY="$(env_get OPENROUTER_API_KEY || true)"
LIVE_BUDGET_USD="$(env_get LIVE_BUDGET_USD || true)"
CRAMIN_KEYSTORE_PROPERTIES="$(env_get CRAMIN_KEYSTORE_PROPERTIES || true)"

echo "== Ключ OpenRouter =="
echo "Совет: создайте в OpenRouter (https://openrouter.ai/settings/keys) отдельный ключ"
echo "для разработки с лимитом кредита, чтобы утечка или ошибка в тестах стоила недорого."
if [ -n "$OPENROUTER_API_KEY" ] && ask_keep OPENROUTER_API_KEY; then
  :
else
  while :; do
    read -rsp "OPENROUTER_API_KEY (ввод скрыт): " OPENROUTER_API_KEY; echo
    [ -n "$OPENROUTER_API_KEY" ] && break
    echo "Пустое значение. Повторите."
  done
fi

echo
echo "== Бюджет живых тестов =="
if [ -n "$LIVE_BUDGET_USD" ] && ask_keep LIVE_BUDGET_USD; then
  :
else
  read -rp "LIVE_BUDGET_USD [1.00]: " LIVE_BUDGET_USD
  LIVE_BUDGET_USD="${LIVE_BUDGET_USD:-1.00}"
fi
[[ "$LIVE_BUDGET_USD" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "LIVE_BUDGET_USD должен быть числом, например 1.00"

# --- Необязательный блок: релизная подпись -----------------------------------
echo
read -rp "Создать релизный keystore и настроить подпись? [y/N] " want_signing
if [[ "${want_signing:-N}" =~ ^[Yy] ]]; then
  read -rp "Каталог хранения вне репозитория [~/.cramin]: " store_dir
  store_dir="${store_dir:-$HOME/.cramin}"
  store_dir="${store_dir/#\~/$HOME}"
  case "$store_dir" in
    "$(pwd)"|"$(pwd)"/*) die "каталог keystore не должен лежать внутри репозитория" ;;
  esac
  mkdir -p "$store_dir"; chmod 700 "$store_dir"
  keystore_path="$store_dir/release.p12"
  props_path="$store_dir/keystore.properties"
  [ -e "$keystore_path" ] && die "файл $keystore_path уже существует — не перезаписываю. Удалите его сами, если он не нужен."

  while :; do
    read -rsp "Пароль keystore (ввод скрыт): " ks_pass; echo
    read -rsp "Повторите пароль: " ks_pass2; echo
    [ "$ks_pass" = "$ks_pass2" ] && [ ${#ks_pass} -ge 8 ] && break
    echo "Пароли не совпадают или короче 8 символов. Повторите."
  done
  unset ks_pass2
  read -rp "CN для сертификата (например, Cramin Release) [Cramin Release]: " cert_cn
  cert_cn="${cert_cn:-Cramin Release}"

  # PKCS12: один пароль на store и key; пароли передаются через окружение,
  # а не через argv, чтобы не светились в ps (SPEC §12.3).
  export CRAMIN_KS_PASS="$ks_pass"
  keytool -genkeypair -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10950 \
    -alias "$KEY_ALIAS" -keystore "$keystore_path" \
    -storepass:env CRAMIN_KS_PASS -keypass:env CRAMIN_KS_PASS \
    -dname "CN=${cert_cn}, O=PerfectProduct, C=IL"
  chmod 600 "$keystore_path"

  ( umask 077; printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
      "$keystore_path" "$ks_pass" "$KEY_ALIAS" "$ks_pass" > "$props_path" )
  chmod 600 "$props_path"
  CRAMIN_KEYSTORE_PROPERTIES="$props_path"

  # Публичный отпечаток сертификата — единственное, что попадает в репозиторий.
  mkdir -p release-signing
  fingerprint="$(keytool -list -v -keystore "$keystore_path" -storepass:env CRAMIN_KS_PASS -alias "$KEY_ALIAS" \
      | sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n1 | tr -d '\r')"
  [ -n "$fingerprint" ] || die "не удалось вычислить SHA-256 отпечаток сертификата"
  printf '%s\n' "$fingerprint" > release-signing/cert-sha256.txt
  echo "SHA-256 отпечаток записан в release-signing/cert-sha256.txt (публичные данные):"
  echo "  $fingerprint"

  if gh auth status >/dev/null 2>&1; then
    echo "Ставлю секреты GitHub Actions в $REPO_SLUG…"
    base64 -w0 "$keystore_path" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO_SLUG"
    printf '%s' "$ks_pass" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO_SLUG"
    printf '%s' "$KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO_SLUG"
    echo "Секреты ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS установлены."
  else
    cat <<EOF
gh не авторизован. Поставьте секреты вручную (Settings → Secrets and variables → Actions):
  ANDROID_KEYSTORE_BASE64   = base64 -w0 $keystore_path
  ANDROID_KEYSTORE_PASSWORD = пароль keystore
  ANDROID_KEY_ALIAS         = $KEY_ALIAS
или после gh auth login:
  base64 -w0 "$keystore_path" | gh secret set ANDROID_KEYSTORE_BASE64 --repo $REPO_SLUG
  printf '%s' '<пароль>' | gh secret set ANDROID_KEYSTORE_PASSWORD --repo $REPO_SLUG
  printf '%s' '$KEY_ALIAS' | gh secret set ANDROID_KEY_ALIAS --repo $REPO_SLUG
EOF
  fi
  unset ks_pass CRAMIN_KS_PASS
fi

# --- Атомарная запись .env -----------------------------------------------------
tmp="$(umask 077; mktemp .env.XXXXXX)"
{
  printf 'OPENROUTER_API_KEY=%s\n' "$OPENROUTER_API_KEY"
  printf 'LIVE_BUDGET_USD=%s\n' "$LIVE_BUDGET_USD"
  printf 'CRAMIN_KEYSTORE_PROPERTIES=%s\n' "$CRAMIN_KEYSTORE_PROPERTIES"
} > "$tmp"
chmod 600 "$tmp"
mv -f "$tmp" .env
unset OPENROUTER_API_KEY LIVE_BUDGET_USD CRAMIN_KEYSTORE_PROPERTIES

echo
echo ".env записан (права 600). Значения не печатаются."
if [[ "${want_signing:-N}" =~ ^[Yy] ]]; then
  cat <<'EOF'

Напоминание по keystore:
  • сделайте бэкап release.p12 в двух местах (потеря = невозможность обновить приложение);
  • пароль храните отдельно от файла;
  • отпечаток сверяйте командой:  keytool -list -v -keystore ~/.cramin/release.p12 -alias cramin-release
EOF
fi
