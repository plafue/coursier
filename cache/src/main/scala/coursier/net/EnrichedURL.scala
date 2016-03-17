package coursier.net

import java.net.{URL, URLConnection}

class EnrichedURL(url: URL) {
  def openConnection(): URLConnection =
    ProxyConfigProvider
      .proxyForUrl(url)
      .map(url.openConnection)
      .getOrElse(url.openConnection)
}
