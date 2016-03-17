package coursier.net

import java.net.{InetSocketAddress, Proxy, URL}

object ProxyConfigProvider {

  private val maybeHttpProxy = loadPossibleProxyFromEnv("http_proxy")
  private val maybeHttpsProxy = loadPossibleProxyFromEnv("https_proxy")
  private val maybeFtpProxy = loadPossibleProxyFromEnv("ftp_proxy")

  def proxyForUrl(url: URL): Option[Proxy] = url.getProtocol match {
    case "http"  => maybeHttpProxy
    case "https" => maybeHttpsProxy
    case "ftp"   => maybeFtpProxy orElse maybeHttpProxy
  }

  private def loadPossibleProxyFromEnv(envProperty: String): Option[Proxy] =
    Option(System.getenv(envProperty))
      .map(new URL(_))
      .map {
        url =>
          new Proxy(
            Proxy.Type.HTTP,
            new InetSocketAddress(url.getHost, url.getPort))
      }
}
