# UbuntuBox 环境设置（每次启动应用时会被覆盖）
umask 022
export LANG=C.UTF-8
export COLORTERM=truecolor
case " ${NODE_OPTIONS:-} " in
  *node-compat.cjs*) ;;
  *) export NODE_OPTIONS="--require=/usr/local/lib/ubox/node-compat.cjs ${NODE_OPTIONS:-}" ;;
esac
if [ -t 1 ] && [ -n "${PS1:-}" ] && [ ! -e /root/.hushlogin ]; then
  cat /etc/ubox-motd
fi
