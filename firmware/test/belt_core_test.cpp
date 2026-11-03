// Host test of the belt firmware core: replays vectors.txt (made by GenVectors.kt from the Kotlin reference model)
// through belt::Core and checks every result and every motor duty. Build and run:
//   g++ -std=c++17 -O2 -I../basir_belt belt_core_test.cpp -o belt_core_test && ./belt_core_test vectors.txt
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include "belt_core.h"

int main(int argc, char** argv) {
  std::ifstream in(argc > 1 ? argv[1] : "vectors.txt");
  if (!in) { std::printf("no vectors\n"); return 2; }
  belt::Core core;
  std::string line;
  long frames = 0, checks = 0, bad = 0, ln = 0;
  while (std::getline(in, line)) {
    ln++;
    if (line.empty()) continue;
    std::istringstream s(line);
    char kind; s >> kind;
    if (kind == 'C') { core.onConnect(); continue; }
    if (kind == 'X') { core.onDisconnect(); continue; }
    if (kind == 'F') {
      std::string hex, expect; long long at; s >> hex >> at >> expect;
      std::vector<uint8_t> b(hex.size() / 2);
      for (size_t i = 0; i < b.size(); i++) b[i] = (uint8_t)std::strtoul(hex.substr(2 * i, 2).c_str(), nullptr, 16);
      belt::Result r = core.receive(b.data(), (int)b.size(), at);
      frames++;
      if (expect != belt::resultName(r)) { if (bad < 10) std::printf("line %ld: got %s expected %s\n", ln, belt::resultName(r), expect.c_str()); bad++; }
    } else if (kind == 'D') {
      long long at; s >> at;
      int want[5]; for (int& w : want) s >> w;
      uint8_t d[5]; core.duties(at, d);
      checks++;
      for (int m = 0; m < 5; m++) if (d[m] != want[m]) {
        if (bad < 10) std::printf("line %ld: M%d duty %d expected %d\n", ln, m + 1, d[m], want[m]);
        bad++; break;
      }
    }
  }
  std::printf("frames %ld, duty checks %ld, mismatches %ld\n", frames, checks, bad);
  if (bad == 0 && frames > 1000) std::printf("BELT_CORE_MATCHES_KOTLIN\n");
  return bad == 0 ? 0 : 1;
}
