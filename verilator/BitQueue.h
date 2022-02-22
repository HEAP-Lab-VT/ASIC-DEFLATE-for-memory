#ifndef BITQUEUE_H
#define BITQUEUE_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

struct BitQueue {
  // elements pop from head
  // elems point from head to tail
  struct BitQueueElem *head;
  struct BitQueueElem *tail;
  
  uint8_t headSize;
  uint8_t tailSize;
};

struct BitQueueElem {
  // head bit in lsb
  uint64_t bits;
  // points toward tail, or NULL if this is the tail
  struct BitQueueElem *next;
};

extern void bq_init(struct BitQueue *queue);

extern char bq_pushTail(struct BitQueue *queue, bool bit);

extern char bq_pushHead(struct BitQueue *queue, bool bit);

extern char bq_pop(struct BitQueue *queue, bool *bit);

extern bool bq_isEmpty(struct BitQueue *queue);

extern size_t bq_size(struct BitQueue *queue);

#endif
