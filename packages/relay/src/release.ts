/**
 * APK 发布：路径常量、文件名规则与版本清单的形状。
 *
 * <p>为什么这部分单独成文件：清单是**跨端契约**——中继写它、手机读它，而手机那边
 * （`Model.Release`）只认这八个字段。契约的形状必须是可单测的纯函数，不能散落在
 * HTTP 处理里靠「照着旧服务器抄一遍」维持一致。
 *
 * <p>历史包袱：以前这串 JSON 由 `scripts/release-android.sh` 在 static-host 上
 * `cat > version.json` 手写出来，字段名只存在于那段 heredoc 里。现在中继自己也托管
 * 安装包（`/control/release` 上传、`{@link RELEASE_PREFIX}` 下载），清单就成了服务端
 * 状态的一部分，必须有一个权威生成点。
 */

/** 下载面（相对于中继根地址）。公开可读：手机检查更新时还没登录。 */
export const RELEASE_PREFIX = '/api/dsh-mobile-sh-apk/downloads';

/** 永远指向最新一个版本的稳定链接（手工分发、旧客户端都用得上）。 */
export const RELEASE_LATEST_ALIAS = 'latest.apk';

/** 单次上传的字节上限（`RELAY_RELEASE_MAX_MB` 的缺省值换算而来见 configFromEnv）。 */
export const RELEASE_MAX_MB_DEFAULT = 64;

/** 卷上保留多少个版本（`RELAY_RELEASE_KEEP`）。再多也没人装，只是占盘。 */
export const RELEASE_KEEP_DEFAULT = 5;

/**
 * 版本号白名单。
 *
 * <p>它同时是**文件名的白名单**：`releaseFileName()` 把版本号拼进路径，如果这里
 * 放过 `..`、`/` 或空字节，上传接口就等于开了一个任意写文件的口子。首字符必须是
 * 数字（版本号不会以 `-` 开头），后面只留字母数字与 `. + -`。
 */
const VERSION_RE = /^[0-9][0-9A-Za-z.+-]{0,31}$/;

export function isValidVersion(version: string): boolean {
  return VERSION_RE.test(version);
}

/** 卷上的文件名。带版本号而不是固定名：安装器按路径/大小/时间戳判断「要不要换」，同一个名字会让它复用上一份。 */
export function releaseFileName(version: string): string {
  if (!isValidVersion(version)) throw new Error(`invalid-version:${version}`);
  return `dsh-mobile-${version}.apk`;
}

/** 说明文字落进清单前要收敛：换行会被 App 的单行文案吃掉，控制字符进 JSON 也是麻烦。 */
export function normalizeNotes(notes: string | undefined): string {
  return (notes ?? '').replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 400);
}

/** 版本清单用的 `created_at` → ISO 时间字符串（App 只显示，不解析）。 */
export function publishedAtOf(nowMs: number): string {
  return new Date(nowMs).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/**
 * 台账行（`releases` 表的一行）。
 *
 * <p>用宽松的索引签名而不是把六个字段都声明成必填：`Store.get/all` 返回的是
 * `Record<string, unknown>`（SQLite 列名到值），声明必填字段反而让调用点要到处
 * 断言。取值一律走 `String(x ?? '')` / `Number(x ?? 0)`，缺列时给出安全默认。
 */
export type ReleaseRowLike = Record<string, unknown>;

/**
 * 版本清单（`version.json`）的内容。
 *
 * <p>字段顺序与旧服务器保持一字不差：手机侧解析按名字取，但运维常年用 `curl | jq`
 * 读它，顺序变了容易让人一眼看错。`url` 与 `latestUrl` 都指到**本中继**——这正是
 * 「切到中继、旧地址做回落」的前提：清单从哪来，包就从哪下。
 */
export function manifestOf(row: ReleaseRowLike, baseUrl: string) {
  const version = String(row.version);
  return {
    latest: version,
    minSupported: String(row.min_supported ?? ''),
    url: `${baseUrl}/${releaseFileName(version)}`,
    latestUrl: `${baseUrl}/${RELEASE_LATEST_ALIAS}`,
    size: Number(row.byte_size ?? 0),
    sha256: String(row.sha256 ?? ''),
    notes: String(row.notes ?? ''),
    publishedAt: String(row.published_at ?? ''),
  };
}

/** APP 更新清单的固定路径（`RELEASE_PREFIX + '/version.json'`）。 */
export const RELEASE_MANIFEST_PATH = `${RELEASE_PREFIX}/version.json`;
